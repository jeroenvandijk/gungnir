(ns gungnir.migration
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [gungnir.query :as q]
   [gungnir.database :refer [*datasource*]]
   [honey.sql :as sql]
   ragtime.core
   ragtime.jdbc
   ragtime.reporter
   ragtime.strategy))

(defn special-format
  ([expr] (special-format expr {}))
  ([expr opts]
   (-> expr
       (sql/format (merge {:quoted-snake true} opts))
       (update 0 #(string/replace % "?" "%s"))
       (->> (apply format)))))

(defn- primary-key? [field]
  (get-in field [1 1 :primary-key] false))

(defn- pk-caller [opts]
  (when (:primary-key opts)
    (sql/call :primary-key)))

(defn- optional-caller [opts]
  (when-not (:optional opts)
    (sql/call :not nil)))

(defn- unique-caller [opts]
  (when (:unique opts)
    (sql/call :unique)))

(defn- default-caller [opts]
  (when-let [default (:default opts)]
    (sql/call :default default)))

(defn- references-caller [opts]
  (when-let [references (:references opts)]
    (sql/call :references
              (keyword (namespace references))
              (keyword (name references)))))

(defn- add-default-pk [primary-key field]
  (cond
    (or (false? primary-key)
        (some primary-key? field))
    field
    (= :uuid primary-key)
    (cons [:column/add [:id {:primary-key true :default true} :uuid]] field)
    :else
    (cons [:column/add [:id {:primary-key true} :bigserial]] field)))

(defn- add-column [acc expr]
  (apply q/add-column acc (remove nil? expr)))

(defn- add-create-column [acc expr]
  (conj acc (remove nil? expr)))

(defmulti process-table-column-child
  (fn [[tk ck] _acc field]
    [tk ck (if (coll? field)
             (last field)
             field)]))

(defmulti process-table-column
  (fn [tk _acc [k]]
    [tk k]))

(defmulti format-action first)

(defn- column-serial [column opts]
  [column "SERIAL"
   (pk-caller opts)
   (references-caller opts)
   (optional-caller opts)])

(defmethod process-table-column-child [:table/create :column/add :serial]
  [_ acc [column opts _]]
  (add-create-column acc (column-serial column opts)))

(defmethod process-table-column-child [:table/alter :column/add :serial]
  [_ acc [column opts _]]
  (add-column acc (column-serial column opts)))

(defn- column-bigserial [column opts]
  [column "BIGSERIAL"
   (pk-caller opts)
   (unique-caller opts)
   (references-caller opts)
   (optional-caller opts)])

(defmethod process-table-column-child [:table/create :column/add :bigserial]
  [_ acc [column opts _]]
  (add-create-column acc (column-bigserial column opts)))

(defmethod process-table-column-child [:table/alter :column/add :bigserial]
  [_ acc [column opts _]]
  (add-column acc (column-bigserial column opts)))

(defn- column-uuid [column opts]
  [column :uuid
   (when-let [default (:default opts)]
     (if (true? default)
       (sql/call :default [:raw "uuid_generate_v4()"])
       (sql/call :default default)))
   (pk-caller opts)
   (unique-caller opts)
   (references-caller opts)
   (optional-caller opts)])

(defmethod process-table-column-child [:table/create :column/add :uuid]
  [_ acc [column opts _]]
  (add-create-column acc (column-uuid column opts)))

(defmethod process-table-column-child [:table/alter :column/add :uuid]
  [_ acc [column opts _]]
  (add-column acc (column-uuid column opts)))

(defn- column-string [column opts]
  [column (if-let [size (:size opts)]
            (sql/call :varchar size)
            :text)
   (when-let [default (:default opts)]
     (sql/call :default (format "%s" (string/escape default {\' "\\'"}))))
   (pk-caller opts)
   (unique-caller opts)
   (references-caller opts)
   (optional-caller opts)])

(defmethod process-table-column-child [:table/create :column/add :string]
  [_ acc [column opts _]]
  (add-create-column acc (column-string column opts)))

(defmethod process-table-column-child [:table/alter :column/add :string]
  [_ acc [column opts _]]
  (add-column acc (column-string column opts)))

(defn- column-integer [column opts]
  [column :integer
   (default-caller opts)
   (pk-caller opts)
   (unique-caller opts)
   (references-caller opts)
   (optional-caller opts)])

(defmethod process-table-column-child [:table/create :column/add :int]
  [_ acc [column opts _]]
  (add-create-column acc (column-integer column opts)))

(defmethod process-table-column-child [:table/alter :column/add :int]
  [_ acc [column opts _]]
  (add-column acc (column-integer column opts)))

(defn- column-float [column opts]
  [column (sql/call :float (:size opts 8))
   (default-caller opts)
   (pk-caller opts)
   (unique-caller opts)
   (references-caller opts)
   (optional-caller opts)])

(defmethod process-table-column-child [:table/create :column/add :float]
  [_ acc [column opts _]]
  (add-create-column acc (column-float column opts)))

(defmethod process-table-column-child [:table/alter :column/add :float]
  [_ acc [column opts _]]
  (add-column acc (column-float column opts)))

(defn- column-boolean [column opts]
  [column "boolean"
   (default-caller opts)
   (pk-caller opts)
   (unique-caller opts)
   (references-caller opts)
   (optional-caller opts)])

(defmethod process-table-column-child [:table/create :column/add :boolean]
  [_ acc [column opts _]]
  (add-create-column acc (column-boolean column opts)))

(defmethod process-table-column-child [:table/alter :column/add :boolean]
  [_ acc [column opts _]]
  (add-column acc (column-boolean column opts)))

(defn- column-timestamp [column opts]
  (let [defaults {:current-timestamp :CURRENT_TIMESTAMP}]
    [column :TIMESTAMP
     (when-let [default (:default opts)]
       (sql/call :default (get defaults default default)))
     (pk-caller opts)
     (unique-caller opts)
     (references-caller opts)
     (optional-caller opts)]))

(defmethod process-table-column-child [:table/create :column/add :timestamp]
  [_ acc [column opts _]]
  (add-create-column acc (column-timestamp column opts)))

(defmethod process-table-column-child [:table/alter :column/add :timestamp]
  [_ acc [column opts _]]
  (add-column acc (column-timestamp column opts)))

(defn- column-gungnir-timestamps []
  [[:created_at :TIMESTAMP (sql/call :default :CURRENT_TIMESTAMP) (sql/call :not nil)]
   [:updated_at :TIMESTAMP (sql/call :default :CURRENT_TIMESTAMP) (sql/call :not nil)]])

(defmethod process-table-column-child [:table/create :column/add :gungnir/timestamps]
  [_ acc _]
  (reduce add-create-column acc (column-gungnir-timestamps)))

(defmethod process-table-column-child [:table/alter :column/add :gungnir/timestamps]
  [_ acc _]
  (reduce add-column acc (column-gungnir-timestamps)))

(defmethod process-table-column-child [:table/alter :column/drop :gungnir/timestamps]
  [_ acc _]
  (conj acc :created_at :updated_at))

(defmethod process-table-column [:table/create :column/add] [_ acc [_ & columns]]
  (reduce (partial process-table-column-child [:table/create :column/add]) acc columns))

(defmethod process-table-column [:table/alter :column/add] [_ acc [_ & columns]]
  (reduce (partial process-table-column-child [:table/alter :column/add]) acc columns))

(defmethod process-table-column [:table/alter :column/drop] [_ acc [_ & columns]]
  (->> columns
       (reduce (fn [cols x]
                 (if (qualified-keyword? x)
                   (process-table-column-child [:table/alter :column/drop] cols x)
                   (conj cols (flatten [x]))))
               [])
       (reduce q/drop-column acc)))

(defmethod format-action :table/create [[_ {:keys [table if-not-exists primary-key]} & fields]]
  (assert table ":table is required for `:table/create`")
  (let [columns (reduce (partial process-table-column :table/create) []
                        (add-default-pk primary-key fields))]
    (-> (if if-not-exists
          (q/create-table (name table) :if-not-exists)
          (q/create-table table))
        (q/with-columns columns)
        (special-format))))

(defmethod format-action :table/alter [[_ {:keys [table]} & fields]]
  (-> (reduce (partial process-table-column :table/alter)
              (q/alter-table (name table))
              fields)
      (special-format)))

(defmethod format-action :table/drop [[_ _opts table]]
  (format "DROP TABLE %s" (name table)))

(defmethod format-action :extension/create [[_ {:keys [if-not-exists]} extension]]
  (-> (if if-not-exists
        (q/create-extension (name extension) :if-not-exists)
        (q/create-extension (name extension)))
      (special-format {:quoted-snake false})))

(defmethod format-action :extension/drop [[_ _ extension]]
  (format "DROP EXTENSION \"%s\"" (name extension)))

(defn- raw-action? [action]
  (string? action))

(defn- process-action-pre [action]
  (assert (or (string? action)
              (vector? action))
          "Migration action must be either a string or vector.")
  (if (raw-action? action)
    action
    (let [[k ?opts & fields] action]
      (if (map? ?opts)
        (format-action action)
        (format-action (vec (concat [k {} ?opts] fields)))))))

(defn- migration-file? [file]
  (and (string/ends-with? (.getName file) ".edn")
       (.isFile file)))

(defn- file->migration-map [file]
  (-> (slurp file)
      (edn/read-string)
      (assoc :id (subs (.getName file) 0 (- (count (.getName file)) 4)))))

(s/fdef ->migration
  :args (s/cat :migration :gungnir/migration)
  :ret (comp #{ragtime.jdbc.SqlMigration} type))
(defn ->migration [migration]
  (-> migration
      (update :up (partial mapv process-action-pre))
      (update :down (partial mapv process-action-pre))
      (update :id str)
      ragtime.jdbc/sql-migration))

(s/fdef migrate!
  :args (s/alt
         :arity-1 (s/cat :migrations (s/coll-of :gungnir/migration))
         :arity-2 (s/cat :migrations (s/coll-of :gungnir/migration)
                         :opts map?)
         :arity-3 (s/cat :migrations (s/coll-of :gungnir/migration)
                         :opts map?
                         :datasource :sql/datasource))
  :ret nil?)
(defn migrate!
  "Run any `migrations` that haven't been executed yet. An optional
  `datasource` can be provided, defaults to `gungnir.database/*datasource*`.

  `opts` takes the following arguments:

  :strategy - defines what to do if there are conflicts between the migrations
              applied to the data store, and the migrations that need to be
              applied. The default strategy is ragtime.strategy/raise-error.
  :reporter - a function that takes three arguments: the store, the operation
              (:up or :down) and the migration ID. The reporter is a
              side-effectful callback that can be used to print or report on
              the migrations as they are applied. The default reporter is
              ragtime.reporter/silent."
  ([migrations] (migrate! migrations {} *datasource*))
  ([migrations opts] (migrate! migrations opts *datasource*))
  ([migrations opts datasource]
   (let [migrations (mapv ->migration migrations)]
     (ragtime.core/migrate-all
      (ragtime.jdbc/sql-database {:datasource datasource})
      (ragtime.core/into-index {} migrations)
      migrations
      (merge
       {:strategy ragtime.strategy/raise-error
        :reporter ragtime.reporter/silent}
       opts)))))

(s/fdef rollback
  :args (s/alt
         :arity-1 (s/cat :migrations (s/coll-of :gungnir/migration))
         :arity-2 (s/cat :migrations (s/coll-of :gungnir/migration)
                         :datasource :sql/datasource))
  :ret nil?)

(defn rollback!
  "Rollback the last run migration from `migrations`. An optional
  `datasource` can be provided, defaults to `gungnir.database/*datasource*`."
  ([migrations] (rollback! migrations *datasource*))
  ([migrations datasource]
   (let [migrations (mapv ->migration migrations)]
     (ragtime.core/rollback-last
      (ragtime.jdbc/sql-database {:datasource datasource})
      (ragtime.core/into-index {} migrations)))))

(defn load-resources
  "Load any migrations EDN files in the `path` resource directory. A
  migration file ends with the `.edn` extension and contains a map
  with an `:up` and `:down` key."
  [path]
  (->> (io/resource path)
       (io/file)
       (file-seq)
       (sort-by #(.getName %))
       (keep #(when (migration-file? %) (file->migration-map %)))))
