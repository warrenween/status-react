(ns status-im.commands.handlers.jail
  (:require [re-frame.core :refer [after dispatch subscribe trim-v debug]]
            [status-im.utils.handlers :as handlers]
            [status-im.utils.utils :refer [show-popup]]
            [status-im.utils.types :refer [json->clj]]
            [status-im.commands.utils :refer [generate-hiccup reg-handler]]
            [clojure.string :as s]
            [status-im.components.react :as r]
            [status-im.models.commands :as cm]
            [status-im.constants :refer [console-chat-id]]
            [status-im.i18n :refer [get-contact-translated]]
            [taoensso.timbre :as log]
            [status-im.commands.utils :as cu]))

(defn command-handler!
  [_ [chat-id
      {:keys [command-message] :as parameters}
      {:keys [result error]}]]
  (let [{:keys [context returned]} result
        {handler-error :error} returned]
    (cond
      handler-error
      (when-let [markup (:markup handler-error)]
        (dispatch [:set-chat-ui-props {:validation-messages (cu/generate-hiccup markup)}]))

      result
      (let [command'    (assoc command-message :handler-data returned)
            parameters' (assoc parameters :command command')]
        (if (:eth_sendTransaction context)
          (dispatch [:wait-for-transaction (:id command-message) parameters'])
          (dispatch [:prepare-command! chat-id parameters'])))

      (not (or error handler-error))
      (dispatch [:prepare-command! chat-id parameters])

      :else nil)))

(defn suggestions-handler!
  [{:keys [contacts chats] :as db} [{:keys [chat-id default-db command parameter-index result]}]]
  (let [{:keys [markup height] :as returned} (get-in result [:result :returned])
        {:keys [dapp? dapp-url]} (get contacts chat-id)
        path (if command
               [:chats chat-id :parameter-boxes (:name command) parameter-index]
               [:chats chat-id :parameter-boxes :message])]
    (dispatch [:choose-predefined-expandable-height :parameter-box (or (keyword height) :default)])
    (when-not (= (get-in db path) markup)
      (dispatch [:set-in path returned])
      (when default-db
        (dispatch [:update-bot-db {:bot chat-id
                                   :db  default-db}])))))

(defn suggestions-events-handler!
  [{:keys [current-chat-id bot-db] :as db} [[n & data :as ev] val]]
  (log/debug "Suggestion event: " n (first data) val)
  (let [{:keys [dapp?]} (get-in db [:contacts current-chat-id])]
    (case (keyword n)
      :set-command-argument
      (let [[index value move-to-next?] (first data)]
        (dispatch [:set-command-argument [index value move-to-next?]]))
      :set-value
      (dispatch [:set-chat-input-text (first data)])
      :set
      (let [opts {:bot   current-chat-id
                  :path  (mapv keyword data)
                  :value val}]
        (dispatch [:set-in-bot-db opts]))
      :set-command-argument-from-db
      (let [[index arg move-to-next?] (first data)
            path  (keyword arg)
            value (str (get-in bot-db [current-chat-id path]))]
        (dispatch [:set-command-argument [index value move-to-next?]]))
      :set-value-from-db
      (let [path  (keyword (first data))
            value (str (get-in bot-db [current-chat-id path]))]
        (dispatch [:set-chat-input-text value]))
      :focus-input
      (dispatch [:chat-input-focus :input-ref])
      nil)))

(defn print-error-message! [message]
  (fn [_ params]
    (when (:error (last params))
      (show-popup "Error" (s/join "\n" [message params]))
      (log/debug message params))))

(reg-handler :command-handler!
  (after (print-error-message! "Error on command handling"))
  (handlers/side-effect! command-handler!))

(reg-handler
  :suggestions-handler
  [(after (print-error-message! "Error on param suggestions"))]
  (handlers/side-effect! suggestions-handler!))

(reg-handler
  :suggestions-event!
  (handlers/side-effect! suggestions-events-handler!))

(reg-handler :set-local-storage
  (fn [{:keys [current-chat-id] :as db} [{:keys [data] :as event}]]
    (log/debug "Got event: " event)
    (assoc-in db [:local-storage current-chat-id] data)))
