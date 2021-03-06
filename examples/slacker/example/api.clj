(ns slacker.example.api)

(defn timestamp []
  (System/currentTimeMillis))

(def ^{:private true} m (atom 0))
(defn inc-m [amount]
  (swap! m + amount))

(defn get-m []
  @m)

(defn rand-ints
  "test collection result"
  [n]
  (map (fn [_] (rand-int Integer/MAX_VALUE)) (range n)))

(defn make-error
  "test runtime exception"
  []
  (throw (RuntimeException. "Excepted exception.")))

(defn echo [x]
  x)

(defn random-exception [poss]
  (if (< (rand) poss)
    (throw (RuntimeException. "Random Exception"))))

(defn long-running []
  (Thread/sleep 15000)
  true)
