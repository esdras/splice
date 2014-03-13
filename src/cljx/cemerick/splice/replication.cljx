(ns cemerick.splice.replication
  (:require [cemerick.splice :as s :refer (write* tuple->vector)]
            [cemerick.splice.memory :as mem]
            [cemerick.splice.memory.query :as q :refer (q)]
            #+clj [cemerick.splice.memory.planning :refer (plan)]
            #+clj [clojure.core.async :as async :refer (go go-loop >! <! alts!)]
            #+cljs [cljs.core.async :as async :refer (>! <!)]
            [clojure.string :as str])
  #+cljs (:require-macros [cemerick.splice.memory.planning :refer (plan)]
                          [cljs.core.async.macros :refer (go go-loop)]))

; TODO scanning the index directly is more efficient, but we need to be able to
; insert arbitrary queries to define the scope of replication. So, for all
; entities defined by the ACP query, find all writes that affect them >
; since-write, and then proceed with write eligibility from there
(defn- local-writes-since
  [space [site-id _ :as since-write]]
  (->> (s/scan space [:write :e :a :v :remove-write]
         (s/tuple s/index-bottom s/index-bottom s/index-bottom since-write)
         (s/tuple s/index-top s/index-top s/index-top [site-id s/index-top]))
       (drop-while #(= since-write (:write %)))
       (partition-by :write)))

(defn- local-writes-since'
  [space since-write]
  (q space (plan {:select [?t]
                  :args [?since-write]
                  :where [[_ _ _ (< ?since-write ?w) :as ?t]
                          ]}))
  )

; might have gone overboard with the helper fns here...
(defn- named?
  "Returns true if [x] is 'named', i.e. a keyword or symbol."
  [x]
  #+clj (instance? clojure.lang.Named x) #+cljs (satisfies? INamed x))

(defn- attribute-of
  [x]
  (cond
    (named? x) x
    (s/tuple? x) (:a x)))

(defn- attribute-namespace
  [x]
  (some-> x attribute-of namespace (str/split #"\.")))

(defn- local-attribute?
  [x]
  (= "local" (first (attribute-namespace x))))

(defn- replication-eligible-writes
  [write]
  (let [write (remove local-attribute? write)]
    (when-not (= #{["clock"]} (set (map attribute-namespace write)))
      write)))

(defn- replication-eligible-writes-since
  [space since-write]
  (->> (local-writes-since space since-write)
    (map replication-eligible-writes)
    (remove nil?)))

(defn matching-write-channel
  "Returns a channel that yields writes (i.e. collections of tuples, each of
which constitutes a write) made to the space contained in the given [space-ref]
since [since-write] (which may be nil in order to yield all writes ever made to
the space.  Writes are put on the returned channel in the order they were
written to the source space.  The consumer of the returned write channel _must_
put a truthy value onto [control-channel] after consuming each write within 15
seconds in order to have the next write delivered, or the write channel will be
closed.  (This control semantic is deeply flawed, TODO will be revisited.)"
  [space-ref since-write control-channel]
  (let [since-write (or since-write [(mem/site-idq @space-ref) s/index-bottom])
        write-channel (async/chan)
        watch-key (gensym (str "watch-" since-write))
        changes (async/chan (async/dropping-buffer 1))]
    ; ensure that we check for matching writes at least once right away
    (async/put! changes true)

    (add-watch space-ref watch-key
               (fn [watch-key space-ref old-space space]
                 (go (>! changes true))))

    ; TODO if the reader of the write channel goes away, these puts will block,
    ; and the watcher and any set of pending found writes will persist
    ; ~forever. Are timeouts the only mechanism we have to break this
    ; coordination problem?
    (go
      (loop [last-matching-write since-write
             writes nil]
        (if-let [w (first writes)]
          (do (>! write-channel w)
              (when (first (alts! [control-channel (async/timeout 15000)]))
                (recur (:write (first w)) (rest writes)))) 
          (when (<! changes)
            (recur last-matching-write
              (replication-eligible-writes-since @space-ref last-matching-write)))))

      (async/close! write-channel)
      (remove-watch space-ref watch-key))
    
    write-channel))

(defn peering-replication
  [src dest]
  (let [matching-write-control (async/chan)
        ; TODO find dest site-id, start from most recent in src from that site
        writes (matching-write-channel src nil matching-write-control)
        replication-control (async/chan)]
    (go-loop [last-write nil]
      (let [[v from-chan] (alts! [writes replication-control])]
        (if-not (coll? v)
          ; either a "cancel" signal via the control channel, or the matching-write-channel
          ; was closed
          (>! replication-control {:last-write last-write})
          ; TODO checking to see if the replicated write is in dest
          ; already or not stinks of non-idempotency; only relevant b/c
          ; of the local write containing replication-time
          (let [write-eid (:write (first v))]
            (when-not (seq (q/q @dest (plan {:select [?t]
                                               :args [?write]
                                               :where [[?write :clock/wall ?t]]})
                               write-eid))
                (swap! dest s/replicated-write v)) 
              (>! matching-write-control true)
              (recur write-eid)))))
    replication-control))

#_#_#_#_
(def a (atom (mem/in-memory)))
(def b (atom (mem/in-memory)))
(def ctrl (peering-replication a b))
(dotimes [x 20] (swap! a s/write [{::s/e "m" :x x}]))
