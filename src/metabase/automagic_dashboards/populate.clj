(ns metabase.automagic-dashboards.populate
  "Automatically generate questions and dashboards based on predefined
   heuristics."
  (:require [metabase.api
             [common :as api]
             [card :as card.api]]
            [metabase.events :as events]
            [metabase.models.dashboard :as dashboard]
            [toucan
             [db :as db]
             [hydrate :refer [hydrate]]]))

(def ^:private ^Integer grid-width  18)
(def ^:private ^Integer card-width   6)
(def ^:private ^Integer card-height  4)

(defn- next-card-position
  "Return `:row` x `:col` coordinates for the next card to be placed on
   dashboard `dashboard`.
   Assumes a grid `grid-width` cells wide with cards sized
   `card-width` x `card-height`."
  [dashboard]
  (let [num-cards (db/count 'DashboardCard :dashboard_id (:id dashboard))]
    {:row (int (* (Math/floor (/ (* card-width num-cards)
                                 grid-width))
                  card-height))
     :col (int (* (Math/floor (/ (mod (* card-width num-cards) grid-width)
                                 card-width))
                  card-width))}))

(defn create-dashboard!
  "Create dashboard."
  [title description]
  (let [dashboard (db/insert! 'Dashboard
                    :name        title
                    :description description
                    :creator_id  api/*current-user-id*
                    :parameters  [])]
    (events/publish-event! :dashboard-create dashboard)
    dashboard))

(defn- create-collection!
  [name color description]
  (when api/*is-superuser?*
    (db/insert! 'Collection
      :name        name
      :color       color
      :description description)))

(def ^:private automagic-collection
  "Get or create collection used to store all autogenerated cards.
   Value is wrapped in a delay so that we don't hit the DB out of order."
  (delay
   (or (db/select-one 'Collection
                      :name "Automatically Generated Questions")
       (create-collection! "Automatically Generated Questions"
                           "#000000"
                           "Cards used in automatically generated dashboards."))))

(defn- create-card!
  [{:keys [visualization title description query]}]
  (let [[display visualization-settings] visualization
        card (db/insert! 'Card
               :creator_id             api/*current-user-id*
               :dataset_query          query
               :description            description
               :display                display
               :name                   title
               :visualization_settings visualization-settings
               :result_metadata        (card.api/result-metadata-for-query query)
               :collection_id          (-> automagic-collection deref :id))]
    (events/publish-event! :card-create card)
    (hydrate card :creator :dashboard_count :labels :can_write :collection)
    card))

(defn add-to-dashboard!
  "Add card `card` to dashboard `dashboard`."
  [dashboard card]
  (dashboard/add-dashcard! dashboard (create-card! card)
    (merge (next-card-position dashboard)
           {:sizeX card-width
            :sizeY card-height})))
