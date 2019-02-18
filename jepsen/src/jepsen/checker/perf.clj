(ns jepsen.checker.perf
  "Supporting functions for performance analysis."
  (:require [clojure.stacktrace :as trace]
            [clojure.core.reducers :as r]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [info warn]]
            [jepsen.util :as util]
            [jepsen.store :as store]
            [multiset.core :as multiset]
            [gnuplot.core :as g]
            [knossos.core :as knossos]
            [knossos.op :as op]
            [knossos.history :as history]))

(defn bucket-scale
  "Given a bucket size dt, and a bucket number (e.g. 0, 1, ...), returns the
  time at the midpoint of that bucket."
  [dt b]
  (-> b long (* dt) (+ (/ dt 2))))

(defn bucket-time
  "Given a bucket size dt and a time t, computes the time at the midpoint of
  the bucket this time falls into."
  [dt t]
  (bucket-scale dt (/ t dt)))

(defn buckets
  "Given a bucket size dt, emits a lazy sequence of times at the midpoints of
  each bucket."
  ([dt]
   (->> (iterate inc 0)
       (map (partial bucket-scale dt))))
  ([dt tmax]
   (take-while (partial >= tmax) (buckets dt))))

(defn bucket-points
  "Takes a time window dt and a sequence of [time, _] points, and emits a
  seq of [time, points-in-window] buckets, ordered by time. Time is at the
  midpoint of the window."
  [dt points]
  (->> points
       (group-by #(->> % first (bucket-time dt)))
       (into (sorted-map))))

(defn quantiles
  "Takes a sequence of quantiles from 0 to 1 and a sequence of values, and
  returns a map of quantiles to values at those quantiles."
  [qs points]
  (let [sorted (sort points)]
    (when-not (empty? sorted)
      (let [n (count sorted)
            extract (fn [q]
                      (let [idx (min (dec n) (long (Math/floor (* n q))))]
                        (nth sorted idx)))]
        (zipmap qs (map extract qs))))))

(defn latencies->quantiles
  "Takes a time window in seconds, a sequence of quantiles from 0 to 1, and a
  sequence of [time, latency] pairs. Groups pairs by their time window and
  emits a emits a map of quantiles to sequences of [time,
  latency-at-that-quantile] pairs, one per time window."
  [dt qs points]
  (assert (number? dt))
  (assert (every? number? qs))
  (assert (every? #(<= 0 % 1) qs))
  (let [buckets (->> points
                     (bucket-points dt)
                     (map (fn [[bucket-time points]]
                            [bucket-time (quantiles qs (map second points))])))]
    ; At this point we have a sequence of
    ; [time, {q1 v1, q2 ; v2, ...}, ...]
    ; pairs, and we want a map of
    ; {q1 -> [[time, v1], [time2, v2], ...], ...}
    (->> qs
         (map (fn [q]
                (map (fn [[t qs]]
                       [t (get qs q)])
                     buckets)))
         (zipmap qs))))

(defn invokes-by-type
  "Splits up a sequence of invocations into ok, failed, and crashed ops by
  looking at their corresponding completions."
  [ops]
  {:ok   (filter #(= :ok   (:type (:completion %))) ops)
   :fail (filter #(= :fail (:type (:completion %))) ops)
   :info (filter #(= :info (:type (:completion %))) ops)})

(defn invokes-by-f
  "Takes a history and returns a map of f -> ops, for all invocations."
  [history]
  (->> history
       (filter op/invoke?)
       (group-by :f)))

(defn invokes-by-f-type
  "Takes a history and returns a map of f -> type -> ops, for all invocations."
  [history]
  (->> history
       (filter op/invoke?)
       (group-by :f)
       (util/map-kv (fn [[f ops]] [f (invokes-by-type ops)]))))

(defn completions-by-f-type
  "Takes a history and returns a map of f -> type-> ops, for all completions in
  history."
  [history]
  (->> history
       (remove op/invoke?)
       (group-by :f)
       (util/map-kv (fn [[f ops]] [f (group-by :type ops)]))))

(defn rate
  "Map breaking down the mean rate of completions by f and type, plus totals at
  each level."
  [history]
  (->> history
       (r/remove op/invoke?)
       (reduce (fn [m op]
                 (let [f (:f op)
                       t (:type op)]
                   ; slow and bad
                   (-> m
                       (update-in [f t]         util/inc*)
                       (update-in [f ::all]     util/inc*)
                       (update-in [::all t]     util/inc*)
                       (update-in [::all ::all] util/inc*)))))))

(defn latency-point
  "Given an operation, returns a [time, latency] pair: times in seconds,
  latencies in ms."
  [op]
  (list (double (util/nanos->secs (:time op)))
        (double (util/nanos->ms   (:latency op)))))

(defn fs->points
  "Given a sequence of :f's, yields a map of f -> gnuplot-point-type, so we can
  render each function in a different style."
  [fs]
  (->> fs
       (map-indexed (fn [i f] [f (* 2 (+ 2 i))]))
       (into {})))

(defn qs->colors
  "Given a sequence of quantiles q, yields a map of q -> gnuplot-color, so we
  can render each latency curve in a different color."
  [qs]
  (-> qs
      sort
      reverse
      (zipmap (map vector
                   (repeat 'rgb)
                   (cycle ["red"
                           "orange"
                           "purple"
                           "blue"
                           "green"
                           "grey"])))))

(def types
  "What types are we rendering?"
  [:ok :info :fail])

(def type->color
  "Takes a type of operation (e.g. :ok) and returns a gnuplot color."
  {:ok   ['rgb "#81BFFC"]
   :info ['rgb "#FFA400"]
   :fail ['rgb "#FF1E90"]})

(defn nemesis-intervals
  "Given a history, constructs a sequence of [start-time, stop-time] intervals
  when the nemesis was active, in units of seconds."
  ([history]
   (nemesis-intervals history {}))
  ([history opts]
   (let [final-time (-> history
                        rseq
                        (->> (filter :time))
                        first
                        :time
                        (or 0)
                        util/nanos->secs
                        double)
         history (util/nemesis-intervals history opts)]
     (keep (fn [interval]
             (let [[start stop] interval]
               (when start
                 [(-> start :time util/nanos->secs double)
                  (if stop
                    (-> stop :time util/nanos->secs double)
                    final-time)])))
           history))))

(defn nemesis-regions
  "Emits a sequence of gnuplot commands rendering shaded regions where the
  nemesis is active. Takes an options map for nemesis regions and styling ex:

  {:start #{:start1 :start2}
   :stop #{:stop1 :stop2}
   :fill-color #\"000000\"
   :transparency 0.05}"
  ([history]
   (nemesis-regions history {}))
  ([history opts]
   (let [fill-color   (or (:fill-color   opts) "#000000")
         transparency (or (:transparency opts) 0.05)
         history (nemesis-intervals history opts)]
     (->> history
        (map (fn [[start stop]]
               [:set :obj :rect
                :from (g/list start [:graph 0])
                :to   (g/list stop  [:graph 1])
                :fillcolor :rgb fill-color
                :fillstyle :transparent :solid transparency
                :noborder]))))))

(defn nemesis-events
  "Given a history, constructs a sequence of times, in seconds, marking nemesis
  events other than start/stop pairs."
  [history keys]
  (let [start (or (:start keys) #{:start})
        stop  (or (:stop  keys) #{:stop})]
    (->> history
         (filter (fn [op]
                   (and (= :nemesis (:process op))
                        (not (start (:f op)))
                        (not (stop  (:f op))))))
         (map (comp double util/nanos->secs :time)))))

(defn nemesis-lines
  "Emits a sequence of gnuplot commands rendering vertical lines where nemesis
  events occurred. Takes an options map for nemesis regions and styling ex:
  {:start #{:start1 :start2}
   :stop #{:stop1 :stop2}
   :line-color #\"dddddd\"
   :line-width 1}"
  ([history]
   (nemesis-lines history {}))
  ([history opts]
   (let [events      (nemesis-events history opts)
         line-color  (or (:line-color opts) "#dddddd")
         line-width  (or (:line-width opts) "1")]
     (map (fn [t]
            [:set :arrow
             :from (g/list t [:graph 0])
             :to   (g/list t [:graph 1])
             ;; When gnuplot gets alpha rgb we can use this
             ;; :lc :rgb "#f3000000"
             :lc :rgb line-color
             :lw line-width
             :nohead])
          events))))

(defn preamble
  "Shared gnuplot preamble"
  [output-path]
  (concat [[:set :output output-path]
           [:set :term :png, :truecolor, :size (g/list 900 400)]]
          '[[set autoscale]
            [set xlabel "Time (s)"]
            [set key outside top right]]))

(defn latency-preamble
  "Gnuplot commands for setting up a latency plot."
  [test output-path]
  (concat (preamble output-path)
          [[:set :title (str (:name test) " latency")]]
          '[[set ylabel "Latency (ms)"]
            [set logscale y]]))

(defn point-graph!
  "Writes a plot of raw latency data points."
  [test history {:keys [subdirectory nemeses] :as opts}]
  (let [history     (util/history->latencies history)
        datasets    (invokes-by-f-type history)
        fs          (util/polysort (keys datasets))
        fs->points  (fs->points fs)
        ; Order for the key
        key-order   (for [f (util/polysort fs), t types] [f t])
        ; Order for points
        plot-order  (->> key-order
                         (sort-by (comp count (partial get-in datasets)))
                         reverse)
        output-path (.getCanonicalPath (store/path! test
                                                    subdirectory
                                                    "latency-raw.png"))
        ;; Gotta fudge this with default opts to make sure it runs at least once
        nemeses (or nemeses #{{}})]
    (when (seq key-order)
      (try
        (g/raw-plot!
          (concat (latency-preamble test output-path)
                  (mapcat #(nemesis-regions history %) nemeses)
                  (mapcat #(nemesis-lines history %)   nemeses)
                  ; Plot ops
                  [['plot (apply g/list
                                 (concat
                                   ; Plot
                                   (for [[f t] plot-order]
                                     ["-"
                                      'with        'points
                                      'linetype    (type->color t)
                                      'pointtype   (fs->points f)
                                      'notitle])
                                   ; Key
                                   (for [[f t] key-order]
                                     ["-"
                                      'with        'points
                                      'linetype    (type->color t)
                                      'pointtype   (fs->points f)
                                      'title       (str (util/name+ f) " "
                                                        (name t))])))]])
          (concat
            ; Plot
            (for [[f t] plot-order]
              (map latency-point (get-in datasets [f t])))
            ; Key
            (for [[f t] key-order]
              (if (seq (get-in datasets [f t]))
                [[0 -1]] ; Dummy point to force rendering
                []))))

        (catch java.io.IOException _
          (throw (IllegalStateException. "Error rendering plot, verify gnuplot is installed and reachable"))))

      output-path)))

(defn quantiles-graph!
  "Writes a plot of latency quantiles, by f, over time."
  [test history {:keys [subdirectory nemeses]}]
  (let [history     (util/history->latencies history)
        dt          30
        qs          [0.5 0.95 0.99 1]
        datasets    (->> history
                         invokes-by-f
                         ; For each f, emit a map of quantiles to points
                         (util/map-kv
                           (fn [[f ops]]
                             (->> ops
                                  (map latency-point)
                                  (latencies->quantiles dt qs)
                                  (vector f)))))
        fs          (util/polysort (keys datasets))
        fs->points  (fs->points fs)
        qs->colors  (qs->colors qs)
        output-path (.getCanonicalPath
                     (store/path! test
                                  subdirectory
                                  "latency-quantiles.png"))
        ;; Gotta fudge this with default opts to make sure it runs at least once
        nemeses (or nemeses #{{}})]
    (when (seq datasets)
      (try
        (g/raw-plot!
          (concat (latency-preamble test output-path)
                  (mapcat #(nemesis-regions history %) nemeses)
                  (mapcat #(nemesis-lines history %)   nemeses)
                  ; Plot ops
                  [['plot (apply g/list
                                 (for [f fs, q qs]
                                   ["-"
                                    'with        'linespoints
                                    'linetype    (qs->colors q)
                                    'pointtype   (fs->points f)
                                    'title       (str (util/name+ f) " "
                                                      q)]))]])
          (for [f fs, q qs]
            (get-in datasets [f q])))

        (catch java.io.IOException _
          (throw (IllegalStateException. "Error rendering plot, verify gnuplot is installed and reachable"))))

      output-path)))

(defn rate-preamble
  "Gnuplot commands for setting up a rate plot."
  [test output-path]
  (concat (preamble output-path)
          [[:set :title (str (:name test) " rate")]]
          '[[set ylabel "Throughput (hz)"]]))

(defn rate-graph!
  "Writes a plot of operation rate by their completion times."
  [test history {:keys [subdirectory nemeses]}]
  (let [dt          10
        td          (double (/ dt))
        t-max       (->> history (r/map :time) (reduce max 0) util/nanos->secs)
        datasets    (->> history
                         (r/remove op/invoke?)
                         ; Don't graph nemeses
                         (r/filter (comp integer? :process))
                         ; Compute rates
                         (reduce (fn [m op]
                                   (update-in m [(:f op)
                                                 (:type op)
                                                 (bucket-time dt
                                                              (util/nanos->secs
                                                               (:time op)))]
                                              #(+ td (or % 0))))
                                 {}))
        fs          (util/polysort (keys datasets))
        fs->points  (fs->points fs)
        output-path (.getCanonicalPath (store/path! test
                                                    subdirectory
                                                    "rate.png"))
        ;; Gotta fudge this with default opts to make sure it runs at least once
        nemeses (or nemeses #{{}})]
    (when (seq datasets)
      (try
        (g/raw-plot!
          (concat (rate-preamble test output-path)
                  (mapcat #(nemesis-regions history %) nemeses)
                  (mapcat #(nemesis-lines history %)   nemeses)
                  ; Plot ops
                  [['plot (apply g/list
                                 (for [f fs, t types]
                                   ["-"
                                    'with         'linespoints
                                    'linetype     (type->color t)
                                    'pointtype    (fs->points f)
                                    'title        (str (util/name+ f) " "
                                                       (name t))]))]])
          (for [f fs, t types]
            (let [m (get-in datasets [f t])]
              (->> (buckets dt t-max)
                   (map (juxt identity #(get m % 0)))))))

        (catch java.io.IOException _
          (throw (IllegalStateException. "Error rendering plot, verify gnuplot is installed and reachable")))))))
