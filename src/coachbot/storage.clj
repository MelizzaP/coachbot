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

(ns coachbot.storage
  (:require [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.java.jdbc :as jdbc]
            [honeysql.core :as sql]
            [honeysql.helpers :as h]))

(defn get-access-tokens [ds team-id]
  (let [query (-> (h/select [:access_token "access_token"]
                            [:bot_access_token "bot_access_token"])
                  (h/from :slack_teams)
                  (h/where [:= :team_id team-id])
                  sql/format)
        [{:keys [access_token bot_access_token]}] (jdbc/query ds query)]
    [access_token bot_access_token]))

(defn get-bot-user-id [ds team-id]
  (let [query (-> (h/select [:access_token "bot_user_id"])
                  (h/from :slack_teams)
                  (h/where [:= :team_id team-id])
                  sql/format)
        [{:keys [bot_user_id]}] (jdbc/query ds query)]
    bot_user_id))

(defn is-bot-user? [ds team-id user]
  (= (get-bot-user-id ds team-id) user))

(defn store-slack-auth! [ds {:keys [team-id] :as auth-data}]
  (jdbc/with-db-transaction
    [conn ds]
    (let [new-record (cske/transform-keys csk/->snake_case
                                          auth-data)
          [a _] (get-access-tokens ds team-id)]
      (if a
        (jdbc/update! conn :slack_teams new-record ["team_id = ?" team-id])
        (jdbc/insert! conn :slack_teams new-record)))))