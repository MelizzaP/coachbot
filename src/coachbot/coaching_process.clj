;
; Copyright (c) 2016, Courage Labs, LLC.
;
; This file is part of CoachBot.
;
; CoachBot is free software: you can redistribute it and/or modify
; it under the terms of the GNU Affero General Public License as published by
; the Free Software Foundation, either version 3 of the License, or
; (at your option) any later version.
;
; CoachBot is distributed in the hope that it will be useful,
; but WITHOUT ANY WARRANTY; without even the implied warranty of
; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
; GNU Affero General Public License for more details.
;
; You should have received a copy of the GNU Affero General Public License
; along with CoachBot.  If not, see <http://www.gnu.org/licenses/>.
;

(ns coachbot.coaching-process
  (:require [clj-cron-parse.core :as cp]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojurewerkz.quartzite.jobs :as qj]
            [clojurewerkz.quartzite.schedule.cron :as qc]
            [clojurewerkz.quartzite.scheduler :as qs]
            [clojurewerkz.quartzite.triggers :as qt]
            [coachbot.db :as db]
            [coachbot.env :as env]
            [coachbot.messages :as messages]
            [coachbot.slack :as slack]
            [coachbot.storage :as storage]
            [taoensso.timbre :as log]))

(defn stop-coaching! [team-id channel user-id _]
  (let [ds (db/datasource)
        [access-token bot-access-token]
        (storage/get-access-tokens ds team-id)

        user (slack/get-user-info access-token user-id)]
    (storage/remove-coaching-user! ds user)
    (slack/send-message! bot-access-token channel messages/coaching-goodbye)))

(defn register-custom-question! [team-id user-id question]
  (let [ds (db/datasource)
        [access-token _]
        (storage/get-access-tokens ds team-id)

        user (slack/get-user-info access-token user-id)]
    (storage/add-custom-question! ds user question)))

(defn- with-sending-constructs [user-id team-id channel f]
  (let [ds (db/datasource)

        [_ bot-access-token]
        (storage/get-access-tokens (db/datasource) team-id)

        send-fn (partial slack/send-message! bot-access-token
                         (or channel user-id))]
    (f ds send-fn)))

(defn send-question!
  "Sends a new question to a specific individual."
  [{:keys [id asked-qid team-id] :as user} & [channel]]

  (with-sending-constructs
    id team-id channel
    (fn [ds send-fn]
      (storage/next-question-for-sending! ds asked-qid user send-fn))))

(defn- send-next-or-resend-prev-question!
  ([user] (send-next-or-resend-prev-question! user nil))
  ([{:keys [id asked-qid answered-qid
            team-id]
     :as user} channel]
   (with-sending-constructs
     id team-id channel
     (fn [ds send-fn]
       (if (= asked-qid answered-qid)
         (storage/next-question-for-sending! ds asked-qid user send-fn)
         (storage/question-for-sending ds asked-qid user send-fn))))))

(defn send-question-if-conditions-are-right!
  "Sends a question to a specific individual only if the conditions are
   right. Checks if the previous question was asked before deciding to re-send
   that one or send a new one. Also checks if the current time is on or after
   the time that the user requested a question to be asked, in their requested
   timezone, of either the last time they were asked a question or the day
   their user record was created relative to the beginning of the day."
  [{:keys [id last-question-date created-date coaching-time timezone]
    :as user}
   & [channel]]
  (let [start-time (or last-question-date
                       (t/with-time-at-start-of-day created-date))
        next-date (cp/next-date start-time coaching-time timezone)
        now (env/now)
        should-send-question? (or (t/equal? now next-date)
                                  (t/after? now next-date))
        formatter (tf/formatters :date-time)]
    (log/debugf
      "User %s: ct='%s', tz='%s', st='%s', nd='%s', now='%s', send? %s"
      id coaching-time timezone start-time
      (tf/unparse formatter next-date)
      (tf/unparse formatter now) should-send-question?)

    (when should-send-question?
      (send-next-or-resend-prev-question! user channel))))

(defn start-coaching!
  [team-id channel user-id & [coaching-time]]
  (let [ds (db/datasource)

        [access-token bot-access-token]
        (storage/get-access-tokens ds team-id)

        user-info (slack/get-user-info access-token user-id)]
    (storage/add-coaching-user!
      ds (if coaching-time
           (assoc user-info :coaching-time coaching-time) user-info))
    (slack/send-message! bot-access-token channel messages/coaching-hello)))

(defn submit-text! [team-id user-email text]
  ;; If there is an outstanding for the user, submit that
  ;; Otherwise store it someplace for a live person to review
  (let [ds (db/datasource)
        [_ bot-access-token] (storage/get-access-tokens ds team-id)

        {:keys [id asked-qid asked-cqid]}
        (storage/get-coaching-user ds team-id user-email)]
    (if asked-qid
      (do
        (storage/submit-answer! ds team-id user-email asked-qid asked-cqid text)
        (slack/send-message! bot-access-token id messages/thanks-for-answer))
      (log/warnf "Text submitted but no question asked: %s/%s %s" team-id
                 user-email text))))

(defn- ensure-user [ds access-token team-id user-id]
  (let [{:keys [email] :as user} (slack/get-user-info access-token user-id)
        get-coaching-user #(storage/get-coaching-user ds team-id email)]
    (if-let [result (get-coaching-user)]
      result
      (do
        (storage/add-coaching-user! ds user)
        (storage/remove-coaching-user! ds user)
        (get-coaching-user)))))

(defn next-question!
  ([team_id channel user-id _] (next-question! team_id channel user-id))
  ([team_id channel user-id]
   (let [ds (db/datasource)
         [access-token _] (storage/get-access-tokens ds team_id)
         user (ensure-user ds access-token team_id user-id)]
     (send-question! user channel))))

(defn event-occurred! [team-id email]
  (log/debugf "Event occurred for %s/%s" team-id email))

(defn send-next-question-to-everyone-everywhere! []
  (let [ds (db/datasource)
        users (storage/list-coaching-users-across-all-teams ds)]
    (log/debugf "Users to send to: %d" (count users))
    (doall (map send-question-if-conditions-are-right! users))))

(qj/defjob DailyCoachingJob [ctx]
  (send-next-question-to-everyone-everywhere!))

(defn schedule-individual-coaching! [scheduler]
  (let [job (qj/build
              (qj/of-type DailyCoachingJob)
              (qj/with-identity (qj/key "jobs.coaching.individual")))
        trigger (qt/build
                  (qt/with-identity (qt/key "triggers.every-minute"))
                  (qt/start-now)
                  (qt/with-schedule
                    (qc/schedule (qc/cron-schedule "0 * * ? * *"))))]
    (qs/schedule scheduler job trigger)))