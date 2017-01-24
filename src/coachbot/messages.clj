;
; Copyright (c) 2017, Courage Labs, LLC.
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

(ns coachbot.messages)

(def thanks-for-answer "Thanks for your answer! See you again soon.")
(def unknown-command (str "I'm not sure what you're asking me to do. "
                          "Try 'help' for a list of commands."))
(def coaching-goodbye "No problem! We'll stop sending messages.")
(def coaching-hello
  "Welcome aboard! We'll send coaching questions at %s every day.")