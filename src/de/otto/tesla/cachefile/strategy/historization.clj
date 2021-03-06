(ns de.otto.tesla.cachefile.strategy.historization
  (:require [hdfs.core :as hdfs]
            [de.otto.status :as s]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c]
            [clojure.tools.logging :as log])
  (:import (java.io BufferedWriter OutputStreamWriter IOException)
           (org.joda.time DateTimeZone DateTime)
           (java.util UUID)))

(defn- writer-entry? [c]
  (when (map? c)
    (if-let [writer-values (vals (select-keys c #{:file-path :writer :last-access}))]
      (every? #(not (nil? %)) writer-values))))

(defn- ^DateTimeZone time-zone []
  (DateTimeZone/getDefault))

(defn- ts->time-map [millis]
  (when millis
    (let [date-time (DateTime. millis (time-zone))]
      {:month (.getMonthOfYear date-time)
       :day   (.getDayOfMonth date-time)
       :year  (.getYear date-time)
       :hour  (.getHourOfDay date-time)})))

(defn time->path [{:keys [year month day hour]}]
  [year month day hour])

(defn- find-all-writers
  ([writers]
   (find-all-writers [] writers))
  ([_ node]
   (if (writer-entry? node)
     node
     (flatten (map (fn [[_ sub-writers]]
                     (find-all-writers sub-writers)) node)))))

(defn- current-time []
  (System/currentTimeMillis))

(defn- writer-too-old? [max-age {last-access :last-access}]
  (let [writer-age (- (current-time) last-access)]
    (>= writer-age max-age)))

(defn- unique-id []
  (str (UUID/randomUUID)))

(defn- output-file-path
  ([output-path time-map]
   (output-file-path output-path time-map false))
  ([output-path {:keys [year month day hour]} zero-padded?]
   (if zero-padded?
     (str output-path "/" year "/" (format "%02d" month) "/" (format "%02d" day) "/" (format "%02d" hour) "/" (unique-id) ".hist.gz")
     (str output-path "/" year "/" month "/" day "/" hour "/" (unique-id) ".hist.gz"))))

(defn- new-print-writer ^BufferedWriter [file-path]
  (BufferedWriter. (OutputStreamWriter. (hdfs/output-stream file-path))))

(defn- create-new-writer [output-path the-time zero-padded?]
  (let [file-path (output-file-path output-path the-time zero-padded?)]
    {:writer      (new-print-writer file-path)
     :path        (time->path the-time)
     :write-count 0
     :file-path   file-path
     :last-access (current-time)}))

(defn touch-writer [writer]
  (-> writer
      (assoc :last-access (current-time))
      (update :write-count inc)))

(defn write-line! [{:keys [writer] :as writer-map} msg]
  (doto writer
    (.write msg)
    (.newLine))
  writer-map)

(defn store-writer [{:keys [path] :as writer} writers]
  (swap! writers assoc-in path writer)
  writer)

(defn close-single-writer! [writer path]
  (log/info "Closing writer for path: " path)
  (try
    (.close writer)
    (catch IOException e
      (log/warn e "Could not close writer for path " path))))

(defn is-top-level-path? [path]
  (< (count path) 2))

(defn remove-path! [writers path]
  (if (is-top-level-path? path)
    (swap! writers dissoc (first path))
    (swap! writers update-in (pop path) dissoc (last path))))

(defn remove-closed-path! [writers closed-writer-path]
  (loop [path closed-writer-path]
    (when-not (empty? path)
      (let [the-value (get-in @writers path)]
        (when (or
                (writer-entry? the-value)
                (empty? the-value))
          (remove-path! writers path)
          (recur (pop path)))))))

(defn close-writers!
  ([writers]
   (close-writers! writers (constantly true)))
  ([writers close-writer?]
   (let [all-writers (find-all-writers @writers)]
     (doseq [{:keys [path writer]} (filter close-writer? all-writers)]
       (try
         (close-single-writer! writer path)
         (remove-closed-path! writers path)
         (catch IOException e
           (log/error e "Error occured when closing and flushing writer in: " path)))))))

(defn close-old-writers! [writers max-writer-age]
  (close-writers! writers (partial writer-too-old? max-writer-age)))

(defn lookup-writer-or-create [output-path writers millis zero-padded?]
  (when-let [the-time (ts->time-map millis)]
    (or
      (get-in @writers (time->path the-time))
      (create-new-writer output-path the-time zero-padded?))))

(def default-time-formatter (f/formatter "YYYY-MM-dd HH:mm:ss Z" (t/default-time-zone)))

(defn- with-readable-last-access [{:keys [last-access] :as w}]
  (let [date-time (c/from-long last-access)
        formated-date (f/unparse default-time-formatter date-time)]
    (assoc w :last-access formated-date)))

(defn- without-writer-object [c]
  (if (writer-entry? c)
    (-> (dissoc c :writer :path)
        (with-readable-last-access))
    c))

(defn historization-status-fn [{:keys [last-error writers which-historizer]}]
  (let [writer-details {:writers (clojure.walk/prewalk without-writer-object @writers)}]
    (if-let [{:keys [msg ts exception]} @last-error]
      (s/status-detail
        (keyword which-historizer) :warning (str "Could not write message \"" msg "\" at ts: \"" ts "\" because of error \"" (.getMessage exception) "\"")
        writer-details)
      (s/status-detail
        (keyword which-historizer) :ok "all ok"
        writer-details))))
