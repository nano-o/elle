(ns elle.consistency-model
  "Elle finds anomalies in histories. This namespace helps turn those
  anomalies into claims about what kind of consistency models are supported
  by, or ruled out by, a given history. For sources, see

  - Adya, 'Weak Consistency'
  - Adya, Liskov, O'Neil, 'Generalized Isolation Level Definitions'
  - Bailis, Davidson, Fekete, et al, 'Highly Available Transactions'
  - Cerone, Bernardi, Gotsman, 'A Framework for Transactional Consistency Models with Atomic Visibility'"
  (:require [elle [graph :as g]
                  [util :as util :refer [map-vals]]]
            [clojure [set :as set]])
  (:import (java.util.function Function)
           (io.lacuna.bifurcan Graphs)))

(def implied-anomalies
  "We have lots of different types of anomalies. Some of them imply others--for
  example, when we detect an :internal anomaly, that's *also* a sign of G1a:
  aborted reads. If G1a is present, so is G1, because G1 is defined as the
  union of G1a, G1b, and G1c."
  (g/map->bdigraph
    {; Formally, G0 is also G1.
     :G0          [:G1]
     :G0-process  [:G1-process :G0-realtime] ; Since processes as singlethreaded
     :G0-realtime [:G1-realtime]

     ; G1 is defined in terms of these three anomalies.
     :G1a [:G1]
     :G1b [:G1]
     :G1c [:G1]
     :G1c-process  [:G1-process :G1c-realtime]
     :G1c-realtime [:G1-realtime]

     ; G-single is a special case of G2
     :G-single          [:G2]
     :G-single-process  [:G2-process :G-single-realtime]
     :G-single-realtime [:G2-realtime]

     ; Every G2-item is also a G2, by definition.
     :G2-item           [:G2]
     :G2-item-process   [:G2-process :G2-item-realtime]
     :G2-item-realtime  [:G2-realtime]

     ; TODO: we don't distinguish between G-single which applies to items, vs
     ; G-single on predicates. Right now, our G-singles ARE G2-item, but that's
     ; not necessarily always going to be the case. This means checkers which
     ; look for repeatable read are going to miss G-single, because they want
     ; to see G2-item, but we filter out G-singles from G2 results. What a
     ; mess. Fix this... in elle.txn, not here.

     ; If we see a process violation, we also have a realtime violation,
     ; because processes are single-threaded.
     :G2-process        [:G2-realtime]

     ; The list-append test can find an anomaly which we call
     ; incompatible-order, where two committed read versions couldn't have come
     ; from the same timeline. This implies a dirty read.
     :incompatible-order [:G1a]

     ; Because we work in a richer data model than Adya, we have an extra class
     ; of anomaly that Adya doesn't: a dirty update. Dirty update is basically
     ; like a dirty read, except it affects writes as well. We say it implies a
     ; G1a anomaly, because it's likely that any model which prohibits G1a
     ; would want to prohibit dirty updates as well.
     :dirty-update [:G1a]}))

(defn all-anomalies-implying
  "Takes a collection of anomalies, and yields a set of anomalies which would
  imply any of those anomalies."
  [anomalies]
  (when (seq anomalies)
    (set (g/bfs (partial g/in implied-anomalies) anomalies))))

(defn all-implied-anomalies
  "Takes a collection of anomalies, and yields a set of anomalies implied by
  those."
  [anomalies]
  (when (seq anomalies)
    (set (g/bfs (partial g/out implied-anomalies) anomalies))))

(def canonical-model-names
  "Lots of models have different names, and I can't keep them all straight.
  This structure maps friendly names to canonical ones."
  {
   :consistent-view          :PL-2+    ; Adya
   :conflict-serializable    :PL-3     ; Adya
   :cursor-stability         :PL-CS    ; Adya
   :forward-consistent-view  :PL-FCV   ; Adya
   :monotonic-snapshot-read  :PL-MSR   ; Adya
   :monotonic-view           :PL-2L    ; Adya
   :read-committed           :PL-2     ; I thiiiink Adya means this, but I'm not
                                       ; sure
   :read-uncommitted         :PL-1     ; Ditto, I'm guessing here. This might
                                       ; be a matter of interpretation.
   :repeatable-read          :PL-2.99  ; Adya
   ; We use "serializable" to mean "conflict serializable"
   :serializable             :PL-3
   :snapshot-isolation       :PL-SI    ; Adya
   :strict-serializable      :PL-SS    ; Adya
   :update-serializable      :PL-3U    ; Adya
   })

(def friendly-model-names
  "Maps canonical model names to friendly ones."
  (assoc (->> canonical-model-names
              (map (juxt val key))
              (into {}))
         ; There are multiple options here, and serializable seems
         ; simplest.
         :PL-3 :serializable))

(defn canonical-model-name
  "Returns the canonical name of a consistency model."
  [model]
  (get canonical-model-names model model))

(defn friendly-model-name
  "Returns the friendly name of a consistency model."
  [model]
  (get friendly-model-names model model))

(def models
  "This graph relates consistency models in a hierarchy, such that if a->b in
  the graph, a history which satisfies model a also satisfies model b. See
  https://jepsen.io/consistency for sources."
  (->> ; Transactional models
       {
        ; Might merge this into normal causal later? I'm not sure
        ; how to unify them exactly.
        :causal-cerone          [:read-atomic]                ; Cerone
        :consistent-view        [:cursor-stability            ; Adya
                                 :monotonic-view]             ; Adya
        :conflict-serializable  [:view-serializable]
        :cursor-stability       [:read-committed              ; Bailis
                                 :PL-2]                       ; Adya
        :forward-consistent-view [:consistent-view]           ; Adya
        :PL-2                    [:PL-1]                      ; Adya
        :PL-3                   [:repeatable-read             ; Adya
                                 :update-serializable         ; Adya
                                 :conflict-serializable]      ; Adya
        :update-serializable    [:forward-consistent-view]    ; Adya
        :monotonic-atomic-view  [:read-committed]             ; Bailis
        :monotonic-view         [:PL-2]                       ; Adya
        :monotonic-snapshot-read [:PL-2]                      ; Adya
        :parallel-snapshot-isolation [:causal-cerone]         ; Cerone
        :prefix                 [:causal-cerone]              ; Cerone
        :read-atomic            [:causal-cerone]              ; Cerone
        :read-committed         [:read-uncommitted]           ; SQL
        :repeatable-read        [:cursor-stability            ; Adya
                                 :monotonic-atomic-view]      ; Bailis
        :strict-serializable    [:PL-3                        ; Adya
                                 :serializable                ; Bailis
                                 :linearizable                ; Bailis
                                 :snapshot-isolation]         ; Adya
        :serializable           [:repeatable-read             ; SQL
                                 :snapshot-isolation]         ; Bailis, Cerone
        :snapshot-isolation     [:forward-consistent-view     ; Adya
                                 :monotonic-atomic-view       ; Bailis
                                 :monotonic-snapshot-read     ; Adya
                                 :parallel-snapshot-isolation ; Cerone
                                 :prefix]                     ; Cerone

        ; Single-object (ish) models
        :linearizable          [:sequential]                 ; Bailis
        :sequential            [:causal]                     ; Bailis
        :causal                [:writes-follow-reads         ; Bailis
                                :PRAM]                       ; Bailis

        :PRAM                  [:monotonic-reads             ; Bailis
                                :monotonic-writes            ; Bailis
                                :read-your-writes]}          ; Bailis
       (g/map->bdigraph)
       (g/map-vertices canonical-model-name)))

(defn all-implied-models
  "Takes a set of models, and expands it, using `models`, to a set of all
  models which are implied by any of those models."
  [ms]
  (g/bfs (partial g/out models) ms))

(defn all-impossible-models
  "Takes a set of models which are impossible, and expands it, using `models`,
  to a set of all models which are also impossible."
  [impossible]
  (g/bfs (partial g/in models) impossible))

(defn most-models
  "Given a set of models, and a direction function (g/in or g/out), gives a
  subset of models which imply/are implied by the other models in the set.
  Canonicalizes model names."
  [dir ms]
  ; The graph's not that big. We just brute-force this by searching the full
  ; downstream set. It's not minimal, but it should be good enough.
  (let [ms (map canonical-model-name ms)]
    (reduce (fn [ms model]
              (let [ms' (disj ms model)]
                (if (some ms' (g/bfs (partial dir models) [model]))
                  ; Some other model covers this one.
                  ms'
                  ms)))
            (set ms)
            ms)))

(defn strongest-models
  "Given a set of models, returns a (hopefully smaller) subset of those models
  which implies all the rest. For instance, (strongest-models
  #{:strict-serializable :serializable}) => #{:strict-serializable}."
  [ms]
  (most-models g/in ms))

(defn weakest-models
  "Given a set of models, returns a (hopefully smaller) subset of those models
  which are implied by all the rest. For instance, (weakest-models
  #{:strict-serializable :serializable}) => #{:serializable}."
  [ms]
  (most-models g/out ms))

(def direct-proscribed-anomalies
  "A graph which relates (canonical) consistency models to the anomalies they
  directly proscribe.

  We don't always specify the *full* set of prohibited anomalies for each
  model, just the ones they add over weaker, implied models. This data
  structure is intended to be used in conjunction with `models` and
  `anomaly-graph`."
  (->> {
        :causal-cerone             [:internal :G1a]    ; Cerone (incomplete)
        :cursor-stability          [:G1 :G-cursor]     ; Adya
        :monotonic-view            [:G1 :G-monotonic]  ; Adya
        :monotonic-snapshot-read   [:G1 :G-MSR]        ; Adya
        :consistent-view           [:G1 :G-single]     ; Adya
        :forward-consistent-view   [:G1 :G-SIb]        ; Adya
        :read-atomic               [:internal          ; Cerone (incomplete)
                                    :G1a]              ; Cerone (incomplete)
        :repeatable-read           [:G1 :G2-item]      ; Adya
        :strict-serializable       [:G1-realtime :G2-realtime] ; Adya
        :update-serializable       [:G1 :G-update]     ; Adya
        :parallel-snapshot-isolation [:internal :G1a]  ; Cerone (incomplete)
        :PL-3                      [:G1 :G2]           ; Adya
        :PL-2                      [:G1]               ; Adya
        :PL-1                      [:G0                ; Adya
                                    ; I don't think an Adya history can exist
                                    ; with either of these. We might want to
                                    ; include these in other, non-Adya models
                                    ; too, but I don't understand their
                                    ; formalisms well enough to say for sure.
                                    :duplicate-elements
                                    ; Version orders are supposed to be total
                                    :cyclic-versions]
        :prefix                    [:internal :G1a]    ; Cerone (incomplete)
        :serializable              [:internal]         ; Cerone (incomplete)
        :snapshot-isolation        [:internal          ; Cerone (incomplete)
                                    :G1 :G-SI]         ; Adya
        }
       (g/map->bdigraph)
       (g/map-vertices canonical-model-name)))

(defn anomalies-prohibited-by
  "Takes a collection of consistency models, and returns a set of anomalies
  which can't be present if all of those models are to hold."
  [models]
  (->> ; Canonicalize models
       (map canonical-model-name models)
       ; Take every model implied by those
       all-implied-models
       ; And expand those to proscribed anomalies...
       (mapcat (comp g/->clj (partial g/out direct-proscribed-anomalies)))
       ; And any anomalies which would imply those anomalies.
       all-anomalies-implying
       (into (sorted-set))))

(defn anomalies->impossible-models
  "Takes a collection of anomalies, and returns a set of models which can't
  hold, given those anomalies are present."
  [anomalies]
  (->> ; Consider not just these direct anomalies, but also the anomalies these
       ; ones imply.
       (all-implied-anomalies anomalies)
       ; Map those to consistency models which directly forbid them
       (mapcat (comp g/->clj (partial g/in direct-proscribed-anomalies)))
       ; And expand to implied impossible models
       all-impossible-models
       (into (sorted-set))))

(defn possible-models
  "Given a set of ruled-out models, returns all models that *are* possible."
  [impossible]
  (->> (g/vertices models)
       g/->clj
       (remove (set impossible))))

(defn boundary
  "Takes a set of anomalies, and yields a map like

    {:not       #{:serializable}
     :also-not  #{:strict-serializable}]}

  ... where :not is the weakest set of consistency models invalidated by the
  given anomaly, and also-not is the remaining set of stronger models."
  [anomalies]
  (let [impossible (anomalies->impossible-models anomalies)
        is-not     (weakest-models impossible)]
    {:not       is-not
     :also-not  (into (sorted-set) (remove is-not impossible))}))

(defn friendly-boundary
  "Like boundary, but uses friendly names."
  [anomalies]
  (map-vals #(into (sorted-set) (map friendly-model-name %))
            (boundary anomalies)))
