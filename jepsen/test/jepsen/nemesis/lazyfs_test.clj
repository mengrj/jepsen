(ns jepsen.nemesis.lazyfs-test
  "Tests for the lazyfs nemesis"
  (:require [clojure [pprint :refer [pprint]]
                     [string :as str]
                     [test :refer :all]]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [checker :as checker]
                    [client :as client]
                    [common-test :refer [quiet-logging]]
                    [control :as c]
                    [core :as jepsen]
                    [db :as db]
                    [generator :as gen]
                    [nemesis :as nem]
                    [os :as os]
                    [tests :as tests]
                    [util :as util]]
            [jepsen.control.util :as cu]
            [jepsen.nemesis.lazyfs :as lazyfs]
            [jepsen.os.debian :as debian]))

(defrecord FileSetClient [dir file node]
  client/Client
  (open! [this test node]
    (assoc this
           :file (str dir "/set")
           :node node))

  (setup! [this test]
    this)

  (invoke! [this test op]
    (-> (c/on-nodes test [node]
                    (fn [_ _]
                      (case (:f op)
                        :add  (do (c/exec :echo (str (:value op) " ") :>> file)
                                  (assoc op :type :ok))
                        :read (let [vals (-> (c/exec :cat file)
                                             (str/split #"\s+")
                                             (->> (map util/parse-long)))]
                                (assoc op :type :ok, :value vals)))))
        (get node)))

  (teardown! [this test])

  (close! [this test]))

(defn file-set-client
  "Writes a set to a single file on one node, in the given directory."
  [dir]
  (map->FileSetClient {:dir dir}))

(deftest ^:integration file-set-test
  (let [dir  "/tmp/jepsen/file-set-test"
        test (assoc tests/noop-test
                    :name      "lazyfs file set"
                    :os        debian/os
                    :db        (lazyfs/db dir)
                    :client    (file-set-client dir)
                    :nemesis   (lazyfs/nemesis)
                    :generator (gen/phases
                                 (->> (range)
                                      (map (fn [x] {:f :add, :value x}))
                                      (gen/delay 1/10)
                                      (gen/nemesis
                                        (->> {:type :info
                                              :f    :lose-unfsynced-writes
                                              :value ["n1"]}
                                             repeat
                                             (gen/delay 1)))
                                      (gen/time-limit 100))
                                 (gen/clients {:f :read}))
                    :checker   (checker/set)
                    :nodes     ["n1"])
        test (jepsen/run! test)]
    (pprint (:history test))
    (pprint (:results test))
    (is (false? (:valid? (:results test))))
    (is (pos? (:lost-count (:results test)) 0))))

