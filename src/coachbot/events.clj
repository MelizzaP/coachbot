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

(ns coachbot.events
  (:require [cheshire.core :as json]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [coachbot.coaching-process :as coaching]
            [coachbot.command-parser :as parser]
            [coachbot.db :as db]
            [coachbot.env :as env]
            [coachbot.messages :as messages]
            [coachbot.slack :as slack]
            [coachbot.storage :as storage]
            [compojure.api.sweet :refer :all]
            [ring.util.http-response :refer :all]
            [schema.core :as s]
            [slingshot.slingshot :as ss]
            [taoensso.timbre :as log])
  (:import (java.util.concurrent LinkedBlockingQueue Executors)))

(s/defschema EventMessage
  {s/Any s/Any})

(def ^:private events (atom {}))

(defn defevent [{:keys [command command-text help config-options]} ef]
  (swap! events assoc command
         {:help help :command-text command-text :config-options config-options
          :ef ef}))

(defn- auth-success [& {:keys [access-token bot-access-token] :as auth-data}]
  (storage/store-slack-auth! (db/datasource) auth-data)
  (let [members (slack/list-members access-token)]
    (doseq [{:keys [id name first-name]} members]
      ; don't overrun the slack servers
      (Thread/sleep 500)

      (slack/send-message!
        bot-access-token id
        (format (str "Hello, %s. I'm a coaching robot. To get started, you "
                     "can say 'start coaching', otherwise say 'help' to see "
                     "what commands I respond to.")
                (or first-name name))))))

(defn- handle-unknown-failure [t event]
  (log/errorf t "Unable to handle event: %s" event)
  "Unknown failure")

(defn- handle-parse-failure [text result]
  (log/warnf "Unable to parse command: %s" text)
  (log/debugf "Parse Result: %s" result))

(defn- hello-world [team-id channel user-id _]
  (storage/with-access-tokens (db/datasource) team-id
    [access-token bot-access-token]
    (let [{:keys [first-name name]} (slack/get-user-info access-token user-id)]
      (slack/send-message! bot-access-token channel
                           (str "Hello, " (or first-name name))))))

(defn- help-for-event [event]
  (let [[_ {:keys [command-text help config-options]}] event
        config-options (or config-options {"" help})]
    (map #(let [[option help] %]
            (format " • %s%s -- %s" command-text option help)) config-options)))

(defn- help [team-id channel _ _]
  (storage/with-access-tokens (db/datasource) team-id [_ bot-access-token]
    (slack/send-message!
      bot-access-token channel
      (str "Here are the commands I respond to:\n"
           (str/join "\n" (flatten (map help-for-event @events)))))))

(def ^:private start-time-ptn #"(?i)(\d|1[0-2])( )?(a\.?m\.?|p\.?m\.?)")

(defn translate-start-time [start-time]
  (if start-time
    (let [start-time (str/replace start-time "." "")
          [_ hour _ time-of-day] (re-find start-time-ptn start-time)
          time-of-day (.toLowerCase time-of-day)
          hour (Integer/parseInt hour)
          hour (if (and (not= 12 hour) (= "pm" time-of-day))
                 (+ 12 hour)
                 (if (and (= "am" time-of-day) (= 12 hour)) 0 hour))]
      (format "0 0 %d * * *" hour))
    "0 0 10 * * *"))

(defn- start-coaching! [team-id channel user-id [start-time]]
  (log/debugf "start-coaching! %s %s %s %s" team-id channel user-id start-time)
  (storage/with-access-tokens (db/datasource) team-id [_ bot-access-token]
    (coaching/start-coaching! team-id user-id (translate-start-time start-time))
    (slack/send-message! bot-access-token channel
                         (format messages/coaching-hello
                                 (or start-time "10am")))))

(def hi-cmd "hi")
(def help-cmd "help")
(def start-coaching-cmd "start coaching")
(def stop-coaching-cmd "stop coaching")
(def next-question-cmd "next question")
(def another-question-cmd "another question")
(def show-question-groups-cmd "show question groups")
(def add-to-group-cmd "add to question group")
(def remove-from-group-cmd "remove from question group")

(defevent {:command :hi :command-text hi-cmd
           :help "checks if I'm listening"} hello-world)

(defevent {:command :help :command-text help-cmd
           :help "display this help message"} help)

(defevent {:command :start-coaching :command-text start-coaching-cmd
           :config-options
           {""
            (str "send daily motivational questions at 10am every day in your "
                 "timezone")

            " at {hour}{am|pm}"
            (str "send daily motivational questions at a specific time "
                 "(e.g. 'start coaching at 9am')")}} start-coaching!)

(defevent {:command :stop-coaching :command-text stop-coaching-cmd
           :help "stop sending questions"} coaching/stop-coaching!)

(defevent {:command :next-question :command-text next-question-cmd
           :help "ask a new question"} coaching/next-question!)

(defevent {:command :show-groups :command-text show-question-groups-cmd
           :help "get a list of the question groups available"}
          coaching/show-question-groups)

(defevent {:command :add-group :command-text add-to-group-cmd
           :config-options
           {" {group name}"
            (str "send questions from the given question group instead of "
                 "the default "
                 "(e.g. 'add to question group Time Management')")}}
          coaching/add-to-question-group!)

(defevent {:command :remove-group :command-text remove-from-group-cmd
           :config-options
           {" {group name}"
            (str "stop sending questions from the given question group "
                 "(e.g. 'remove from question group Time Management')")}}
          coaching/remove-from-question-group!)

(defn- respond-to-event [team-id channel user-id text]
  (let [[command & args] (parser/parse-command text)
        {:keys [ef]} (command @events)]
    (log/debugf "Responding to %s / %s" command args)
    (if ef (ef team-id channel user-id args)
           (do (log/errorf "Unexpected command: %s" command)
               "Unhandled command"))))

(defn- reshape-event [{:keys [event callback_id] :as e}]
  (cond
    event
    (let [{:keys [team_id api_app_id type authed_users]
           {:keys [text ts channel event_ts]
            user-id :user
            event_type :type
            event_subtype :subtype} :event} e]
      {:type :event :team-id team_id :channel channel :user-id user-id
       :text text :event-type event_type :event-subtype event_subtype})

    callback_id
    (let [{:keys [response_url]
           {team-id :id} :team
           {channel :id} :channel
           {user-id :id} :user
           [{action-name :name action-value :value}] :actions} e]
      {:type :button :team-id team-id :channel channel :user-id user-id
       :callback-id callback_id
       :action-name action-name :action-value action-value
       :response-url response_url})

    :else (ss/throw+ {:type ::unhandled-event-shape :event e})))

(defn- process-callback [email bot-access-token
                         {:keys [team-id channel user-id
                                 callback-id action-name action-value
                                 response-url] :as callback}]
  (log/infof "Don't know how to handle callbacks yet: %s" callback))

(defn- process-event [email bot-access-token
                      {:keys [team-id channel user-id text] :as event}]
  (ss/try+
    (if-not (storage/is-bot-user? (db/datasource) team-id user-id)
      (try
        (when (slack/is-im-to-me? bot-access-token channel)
          (respond-to-event team-id channel user-id text))
        (finally (coaching/event-occurred! team-id email)))
      "Ignoring message from myself.")
    (catch [:type :coachbot.command-parser/parse-failure] {:keys [result]}
      (handle-parse-failure text result)
      (coaching/submit-text! team-id email text))
    (catch Exception t (handle-unknown-failure t event))))

(defn- process [event]
  (let [{:keys [team-id user-id type] :as final-event}
        (reshape-event event)

        [access-token bot-access-token]
        (storage/get-access-tokens (db/datasource) team-id)

        {:keys [email]} (slack/get-user-info access-token user-id)]
    (case type
      :event (process-event email bot-access-token final-event)
      :button (process-callback email bot-access-token final-event)
      (ss/throw+ {:type ::unhandled-event-type :event-type type
                  :event event}))))

(defn make-queue-if-configured []
  (when (env/event-queue-enabled?)
    (log/infof "Event queue size: %d" @env/event-queue-size)
    (let [q (LinkedBlockingQueue. (int @env/event-queue-size))
          e (Executors/newFixedThreadPool 1)]
      (.submit e (cast Callable
                       #(while true
                          (try
                            (let [evt (.take q)]
                              (log/debugf "Received event: %s" evt)
                              (process evt))
                            (catch Throwable t
                              (log/error t "Unable to process event"))))))
      q)))

(def ^:private event-queue (delay (make-queue-if-configured)))

(defn handle-event [{:keys [token] :as event}]
  (when-not (= token @env/slack-verification-token)
    (ss/throw+ {:type ::access-denied}))

  (if (env/event-queue-enabled?)
    (if (.offer @event-queue event)
      (do
        (log/debugf "Queue depth %d" (.size @event-queue))
        "submitted")
      (ss/throw+ {:type ::queue-full}))
    (process event)))

(defn- log-message [message]
  (log/infof "Message received: %n%s" (with-out-str (pprint/pprint message))))

(defn handle-raw-event [message]
  (log-message message)
  (ss/try+ (let [result
                 (ok (if-let [challenge-response
                              (slack/challenge-response message)]

                       challenge-response
                       (do (handle-event message) nil)))]
             (log/debugf "event result: %s" result) result)
           (catch [:type ::access-denied] _ (unauthorized))
           (catch [:type ::queue-full] _ (service-unavailable))
           (catch [:type ::unhandled-event-shape] {:keys [event]}
             (log/errorf "Unhandled event shape: %s" event)
             (not-implemented))
           (catch [:type ::unhandled-event-type] {:keys [event-type event]}
             (log/errorf "Unhandled event type: %s/%s" event-type event)
             (not-implemented))))

(defroutes event-routes
  (GET "/oauth" []
    :query-params [code :- String]
    :summary "Give Slack our authorization code so we can be helpful!"
    (if (slack/auth-slack code auth-success)
      (ok "Application authorized!")
      (unauthorized)))

  (POST "/event" []
    :body [message EventMessage]
    :summary "Receive an event from Slack"
    (handle-raw-event message))

  (POST "/message" []
    :form-params [payload :- s/Any]
    :summary "Receive a message from a button from Slack"
    (handle-raw-event (-> payload json/parse-string walk/keywordize-keys))))