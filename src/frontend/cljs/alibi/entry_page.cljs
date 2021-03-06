(ns alibi.entry-page
  (:require
    [cljsjs.react]
    [cljsjs.react.dom]
    [alibi.logging :refer [log log-cljs]]
    [alibi.post-new-entry-bar :as post-new-entry-bar]
    [alibi.post-entry-form :as post-entry-form]
    [alibi.activity-graphic :as activity-graphic]
    [alibi.activity-graphic-data-source :as ag-ds]
    [alibi.day-entry-table :as day-entry-table]
    [clojure.string :refer [split]]
    [time.core :refer [expand-time]]
    [cljs.reader]))

(enable-console-print!)
(defn parse-float [v] (js/parseFloat v))

(def view-data
  (let [view-data-input (.getElementById js/document "view-data")
        view-data-parsed (cljs.reader/read-string
                           (.-value view-data-input))]
    view-data-parsed))

(def task-name (get-in view-data [:projects-tasks :tasks-by-id]))
(def project-name (get-in view-data [:projects-tasks :projects-by-id]))

(ag-ds/load! (:activity-graphic view-data))

;(log "view-data %o" view-data)

(def initial-state
  (let [is (:initial-state view-data)
        options (get-in is [:post-new-entry-bar :options])
        options-by-id (into {} (for [{:keys [value] :as opt} options]
                                 [value opt]))]

    (assoc-in is [:post-new-entry-bar :options-by-id] options-by-id)))

;(log "initial state %o" initial-state)

(defonce state (atom initial-state))

(def DateTimeFormatter (.-DateTimeFormatter js/JSJoda))
(def LocalTime (.-LocalTime js/JSJoda))
(def LocalDate (.-LocalDate js/JSJoda))
(def ChronoUnit (.-ChronoUnit js/JSJoda))
(def ZoneId (.-ZoneId js/JSJoda))
(def Instant js/JSJoda.Instant)

(def time-formatter (.ofPattern DateTimeFormatter "HH:mm"))

(defn input-entry [for-state]
  (-> (:post-entry-form for-state)
      (assoc :selected-date (:selected-date for-state)
             :selected-item (:selected-item for-state))))

(defn input-entry->data-entry
  [entry]
  (when entry
    ;(log "entry %o" entry)
    (let [start-time (.parse LocalTime (:startTime entry) time-formatter)
          end-time (.parse LocalTime (:endTime entry) time-formatter)
          duration-secs (.until start-time end-time (.-SECONDS ChronoUnit))
          date (.parse LocalDate (:selected-date entry))
          start (.. date
                    (atTime start-time)
                    (atZone (.systemDefault ZoneId))
                    (toInstant)
                    (epochSecond))
          end (.. date
                  (atTime end-time)
                  (atZone (.systemDefault ZoneId))
                  (toInstant)
                  (epochSecond))]
      {:task-id (get-in entry [:selected-item :taskId])
       :project-id (get-in entry [:selected-item :projectId])
       :billable? (:isBillable entry)
       :comment (:comment entry)
       :user-id 0
       :from start
       :till end
       :duration duration-secs
       :task (task-name (get-in entry [:selected-item :taskId]))
       :project (project-name (get-in entry [:selected-item :projectId]))
       :entry-id (:entry-id entry)})))

(defn epoch->time-str [epoch]
  (.format (LocalTime.ofInstant (Instant.ofEpochSecond epoch))
           time-formatter))

(defn epoch->date-str [epoch]
  (.toString (LocalDate.ofInstant (Instant.ofEpochSecond epoch))))

(defn data-entry->input-entry [entry]
  (when entry
    {:selected-item {:taskId (:task-id entry)
                     :projectId (:project-id entry)}
     :selected-date (epoch->date-str (:from entry))
     :isBillable (:billable? entry)
     :comment (:comment entry)
     :startTime (epoch->time-str (:from entry))
     :endTime (epoch->time-str (:till entry))
     :entry-id (:entry-id entry)}))

(defn reducer
  [prev-state {:keys [action] :as payload}]
  ;(log "reducer %o" payload)
  (->
    (case action
      :select-task
      (assoc prev-state :selected-item (:task payload))

      ;:change-date
      ;(assoc-in prev-state [:post-entry-form :selectedDate] (:date payload))

      :receive-activity-graphic-data
      (-> prev-state
          (assoc :selected-date (:for-date payload))
          (assoc :activity-graphic-data (:data payload)))

      :mouse-over-entry
      (assoc prev-state :activity-graphic-mouse-over-entry (:entry payload))

      :mouse-leave-entry
      (dissoc prev-state :activity-graphic-mouse-over-entry)

      :edit-entry
      (let [entry (:entry payload)]
        (-> prev-state
            (assoc :selected-item (:selected-item entry)
                   :selected-date (:selected-date entry))))

      :cancel-entry
      (dissoc prev-state :selected-item)

      prev-state)
    (update :post-entry-form post-entry-form/reducer payload)))

(defn dispatch!
  [state-atom action]
  (swap! state-atom reducer action))

(defn fetch-ag-data! [for-date]
  (.then
    (ag-ds/get-data (.toString (activity-graphic/find-first-monday for-date)))
    (fn [data]
      ;(log-cljs "received" for-date)
      (dispatch! state {:action :receive-activity-graphic-data
                        :for-date (.toString for-date)
                        :data data}))))

(let [current-state @state]
  (when-not (:activity-graphic-data current-state)
    (log "fetching initial ag data")
    (fetch-ag-data! (get current-state :selected-date))))

(defn render-post-new-entry-bar!
  [for-state]
  (.render
    js/ReactDOM
    (.createElement
      js/React
      post-new-entry-bar/entry-bar-form
      #js
      {:clj-props
       {:options (get-in for-state [:post-new-entry-bar :options])
        :options-by-id (get-in for-state [:post-new-entry-bar :options-by-id])
        :selected-item (:selected-item for-state)

        :on-cancel
        (fn [] (dispatch! state {:action :cancel-entry}))

        :on-select-task
        (fn [selected-task]
          (dispatch! state {:action :select-task
                            :task selected-task}))}})
    (.getElementById js/document "post-new-entry-bar-container")))

(defn on-change-date [new-date]
  (let [new-date' (if (string? new-date) new-date (.toString new-date))]
    ;(dispatch! state {:action :change-date
                      ;:date new-date'})
    (fetch-ag-data! new-date)))

(defn render-post-entry-form!
  [for-state]
  (let [element (.createElement
                  js/React
                  post-entry-form/react-component
                  (clj->js
                    {:input-entry (input-entry for-state)

                     :on-change-comment
                     (fn [comment]
                       (dispatch! state {:action :change-comment
                                         :comment comment}))

                     :on-change-start-time
                     (fn [start-time]
                       (dispatch! state {:action :change-start-time
                                         :start-time start-time}))

                     :on-change-end-time
                     (fn [end-time]
                       (dispatch! state {:action :change-end-time
                                         :end-time end-time}))

                     :on-change-billable?
                     (fn [billable?]
                       (dispatch! state {:action :change-billable?
                                         :billable? billable?}))

                     :on-change-date on-change-date

                     :on-cancel-entry
                     (fn []
                       (dispatch! state {:action :cancel-entry}))

                     :on-form-submit-error
                     (fn [entry]
                       (dispatch! state {:action :entry-form-show-errors
                                         :for-entry entry}))

                     }))]
    (.render
      js/ReactDOM
      element
      (.getElementById js/document "entry-form-react-container"))))

(defn get-entry [state entry-id]
  {:pre [(integer? entry-id)]}
  (let [ag-data (:activity-graphic-data state)]
    (->> ag-data
         (filter #(= (:entry-id %) entry-id))
         first)))

(defn render-activity-graphic!
  [for-state]
  (let [selected-date (get for-state :selected-date)
        selected-entry (-> (input-entry for-state)
                           (post-entry-form/additional-entry)
                           (input-entry->data-entry))

        [html tooltip]
        (activity-graphic/render
          {:project-data
           {:data (:activity-graphic-data for-state)
            :selected-date selected-date}

           :on-change-date on-change-date

           :mouse-over-entry
           (:activity-graphic-mouse-over-entry for-state)

           :on-mouse-over-entry
           #(dispatch! state {:action :mouse-over-entry
                              :entry %})

           :on-mouse-leave-entry
           #(dispatch! state {:action :mouse-leave-entry})

           :on-click-entry #(dispatch!
                              state
                              {:action :edit-entry
                               :entry (data-entry->input-entry
                                        (get-entry for-state %))})

           :additional-entries (when selected-entry [selected-entry])
           :selected-entry (when selected-entry (:entry-id selected-entry))

           })]
        (.render js/ReactDOM html
                 (.getElementById js/document "activity-graphic"))
        (.render js/ReactDOM tooltip
                 (.getElementById js/document "activity-graphic-tooltip-container"))))

(defn render-day-entry-table!
  [for-state]
  (let [new-date (get for-state :selected-date)]
    (day-entry-table/render "day-entry-table" new-date)))


(add-watch
  state :renderer
  (fn [_ _ _ new-state]
    ;(log "rendering %o" new-state)
    (render-post-new-entry-bar! new-state)
    (render-post-entry-form! new-state)
    (render-activity-graphic! new-state)
    (render-day-entry-table! new-state)))

(reset! state @state)
