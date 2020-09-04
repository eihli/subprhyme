(ns com.owoga.prhyme.frp
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [clojure.set :as set]
            [com.owoga.prhyme.core :as p]
            [com.owoga.prhyme.util :as u]
            [com.owoga.prhyme.syllabify :as s]))

(def dictionary
  (line-seq (io/reader (io/resource "cmudict_SPHINX_40"))))

(def thesaurus
  (->> (line-seq (io/reader (io/resource "mthesaur.txt")))
       (map #(string/split % #","))
       (map #(vector (first %) (rest %)))
       (into {})))

(defrecord Word [word syllables syllable-count rimes onsets nuclei])
(defrecord RhymeTarget [word syllables syllable-count rimes onsets nuclei partitions?])
(defrecord RhymeSubTarget [wordphrase syllables syllable-count rimes onsets nuclei
                           rimes? onsets? nuclei? synonyms?])
(defrecord Synonym [syllables target words])

(defn make-word [word]
  (let [syllables (s/syllabify (rest word))
        rimes     (p/rimes syllables)
        onsets    (p/onset+nucleus syllables)
        nuclei    (p/nucleus syllables)]
    (->Word
     (first word)
     syllables
     (count syllables)
     rimes
     onsets
     nuclei)))

(def words (->> dictionary
                (map u/prepare-word)
                (map make-word)))

(def popular-dict
  (set (line-seq (io/reader (io/resource "popular.txt")))))

(def popular (filter #(get popular-dict (string/lower-case (:word %))) words))

(defn merge-phrase-words
  "Given multiple `Word`, like the words for 'well off', create a single `Word`
  that is syllabified as ('well' 'off') rather than as the combined ('weh'
  'loff'). Useful for finding single-word rhymes of multiple-word targets.

  An example: 'war on crime' -> 'turpentine'."
  [phrase phrase-words]
  (loop [merged (first phrase-words)
         phrase-words (rest phrase-words)]
    (cond
      (and (empty? phrase-words) (empty? merged)) nil
      (empty? phrase-words) (assoc merged :word phrase)
      :else (recur (-> merged
                       (assoc :syllables (concat (:syllables merged)
                                                 (:syllables (first phrase-words))))
                       (assoc :syllable-count (+ (:syllable-count merged)
                                                 (:syllable-count (first phrase-words))))
                       (assoc :rimes (concat (:rimes merged)
                                             (:rimes (first phrase-words))))
                       (assoc :onsets (concat (:onsets merged)
                                              (:onsets (first phrase-words))))
                       (assoc :nuclei (concat (:nuclei merged)
                                              (:nuclei (first phrase-words)))))
                   (rest phrase-words)))))

(defn phrase->word
  "Given a word like 'well-off' or a phrase like 'war on poverty', return a Word
  that has the correct syllables, rimes, onsets, and nucleus. This way we can
  rhyme against phrases that aren't in the dictionary, as long as the words that
  make up the phrase are in the dictionary. Returns nil if the word is not in
  the dictionary."
  [words phrase]
  (->> (string/split phrase #"[ -]")
       (map (fn [phrase-word]
              (first (filter (fn [word]
                               (= phrase-word (string/lower-case (:word word))))
                             words))))
       (merge-phrase-words phrase)))

(defn partition-word [word]
  (->> word
       (:syllables)
       (u/partitions)))

(comment
  (u/partitions (:syllables (phrase->word words "war on poverty")))
  )

(defn rimes [words target]
  (into #{}
        (filter (fn [{:keys [rimes]}]
                  (= (last rimes) (last (:rimes target))))
                words)))

(defn onsets [words target]
  (into #{}
        (filter (fn [{:keys [onsets]}]
                  (= (first onsets) (first (:onsets target))))
                words)))

(defn nuclei [words target]
  (into #{}
        (filter (fn [{:keys [nuclei]}]
                  (= (last nuclei) (last (:nuclei target))))
                words)))

(defn consecutive-matching
  "Returns the consecutive matching rhymes of type.

  Given words:
  (D EY Z IY) and (K R EY Z IY)

  the following would be returned for each type:
  rimes: 2, (((IY) (IY)) ((EY) (EY))) - rimes are matched in reverse order
  onsets: 0
  nuclei: 2, (((EY) (EY)) ((IY) (IY))) - nuclei and onsets are matched in order
  "
  [a b type]
  (let [a (if (= type :rimes) (reverse (type a)) (type a))
        b (if (= type :rimes) (reverse (type b)) (type b))]
    (take-while (fn [[x y]] (= x y)) (map list a b))))

(defn sort-rhymes
  "Sorts by the number of consecutive matching rimes, onsets, and nuclei of each
  word."
  [rhymes word]
  (sort (fn [a b]
          (> (apply
              +
              (map #(count (consecutive-matching a word %))
                   [:rimes :onsets :nuclei]))
             (apply
              +
              (map #(count (consecutive-matching b word %))
                   [:rimes :onsets :nuclei]))))
        rhymes))

(defn prhyme
  "Finds rhymes in dictionary `words` of `word` with options
  to match on rimes, onsets, and/or nuclei."
  [words word]
  (let [r (if (:rimes? word) (rimes words word) #{})
        o (if (:onsets? word) (onsets words word) #{})
        n (if (:nuclei? word) (nuclei words word) #{})
        all (set/union r o n)]
    all))

(comment
  (->> (make-word ["foobar" "F" "UW" "B" "AA" "R"])
       (#(assoc % :rimes? true))
       (prhyme words)
       (filter #(= (:syllable-count %) 2))
       (sort-by #(consecutive-matching
                  %
                  (make-word ["foobar" "F" "UW" "B" "AA" "R"])
                  :rimes)))

  (as-> (make-word ["magic beam" "M" "AE" "J" "IH" "K" "B" "IY" "M"]) word
    (into word {:rimes? true})
    (prhyme popular word)
    (mapcat #(matching-synonyms thesaurus % word)
            ["death" "evil" "satan" "devil" "sin" "bad" "hell"
             "guts" "gore" "blood" "demon" "fear" "nightmare"
             "distress" "corpse" "necrotic" "zombie"
             "coma" "monster"]))

  (as-> (make-word ["please turn" "P" "L" "IH" "Z" "T" "ER" "N"]) word
    (into word {:rimes? true})
    (prhyme popular word)
    (mapcat #(matching-synonyms thesaurus % word)
            ["death" "evil" "satan" "devil" "sin" "bad" "hell"
             "guts" "gore" "blood" "demon" "fear" "nightmare"
             "distress" "corpse" "necrotic" "zombie"
             "coma" "monster"]))
  )

(defn matching-syllable-count [n words]
  (filter #(= n (:syllable-count %)) words))

(defn matching-synonyms [thesaurus target words]
  (let [synonyms (get thesaurus target)]
    (filter (fn [word] (some #(re-matches (re-pattern (str "(?i)" %)) (:word word)) synonyms))
            words)))

(defn make-rhyme-subtarget [wordphrase syllables]
  (map->RhymeSubTarget (into
                        (make-word (concat [wordphrase] (flatten syllables)))
                        {:wordphrase wordphrase
                         :syllables syllables
                         :syllable-count (count syllables)
                         :rimes? true})))

(defn phrymo [dictionary phrase]
  (phrase->word dictionary phrase))

(comment
  (->> (phrymo popular "clover")
       (partition-word)
       (first)
       (first))


  (->> (phrymo popular "war on poverty")
       (partition-word)
       (take 3)
       (map (fn [rhyme-target]
              (map (fn [subtarget]
                     (make-rhyme-subtarget "war on poverty" subtarget))
                   rhyme-target)))
       #_(map (fn [rhyme-target]
                (map (fn [rhyme-sub-target]
                       (prhyme popular rhyme-sub-target))
                     rhyme-target))))
  (->> (map->RhymeSubTarget (into (phrase->word words "war")
                                  {:rimes? true
                                   :onsets? true
                                   :nuclei? true}))
       (prhyme popular)
       (matching-syllable-count 1)
       (into #{})
       (set/intersection
        (into #{} (concat (matching-synonyms thesaurus "rich" words)))))
  )
(defn alignment [target word]
  (cond
    (= (last (:rimes target))
       (last (:rimes word)))
    (- (:syllable-count target)
       (count (:rimes word)))

    (= (first (:onsets target))
       (first (:onsets word)))
    0

    :else
    (- (:syllable-count target)
       (count (:rimes word)))))

(defn pad [char n s]
  (apply str (conj (vec (repeat (- n (count s)) char)) s)))

(defn matching-position
  [index syllable-count word]
  (and (= syllable-count (:syllable-count word))
       (= index (:alignment word))))

(defn find-synonyms
  ([thesaurus dict word]
   (find-synonyms thesaurus dict 1 #{word} #{}))
  ([thesaurus dict word degree]
   (find-synonyms thesaurus dict degree #{word} #{}))
  ([thesaurus dict degree words synonyms]
   (cond
     (= degree 0) synonyms

     (nil? (first words))
     (recur thesaurus
            dict
            (dec degree)
            (into #{} (map #(string/lower-case (:word %)) synonyms))
            synonyms)

     :else
     (recur thesaurus
            dict
            degree
            (rest words)
            (set/union
             synonyms
             (let [synonyms (->> (get thesaurus (first words))
                                 (map string/lower-case)
                                 (into #{}))]
               (->> dict
                    (filter #(synonyms (string/lower-case (:word %))))
                    (into #{}))))))))

(comment
  (->> (get thesaurus "war")
       (map string/lower-case))
  (->> (find-synonyms thesaurus words "evil" 2)
       (map :word))
  )

(defn pprint-phrase [phrase-words]
  (let [phrase-words (map #(if (empty? %) '("_") %) phrase-words)
        max-len (apply max (map count phrase-words))
        words-cycles (map cycle phrase-words)]
    (->> (map (partial take max-len) words-cycles)
         (apply map vector))))

(defn pprint-table [phrase-words]
  (let [phrase-words (map #(if (empty? %) '("") %) phrase-words)
        max-word-lens (->> phrase-words
                           (map #(map count %))
                           (map #(apply max %)))
        max-rhyme-count (count (apply max-key count phrase-words))
        fmt-str (->> max-word-lens
                     (map #(+ 3 %))
                     (map #(format "%%-%ds" %))
                     (apply str))
        phrase-words (->> phrase-words
                          (map #(concat % (repeat "")))
                          (map #(take max-rhyme-count %))
                          (apply map vector)
                          (map #(apply format fmt-str %))
                          (#(string/join "\n" %)))]
    phrase-words))

(comment
  (let [targets (map (partial phrase->word words)
                     ["please" "turn" "on" "your" "magic" "beam"])
        synonyms (into #{} (->> (mapcat #(find-synonyms thesaurus words % 2)
                                        ["evil" "war" "death" "corpse"])
                                (map :word)))]
    (->> targets
         (map #(into % {:rimes? true}))
         (map (fn [target]
                (->> (prhyme popular target)
                     (map #(assoc % :target target)))))
         (map (fn [rhyming-words]
                (filter #(= (:syllable-count %) (:syllable-count (:target %)))
                        rhyming-words)))
         (map (fn [rhyming-words]
                (filter #(synonyms (:word %)) rhyming-words)))
         (map (fn [rhyming-words]
                (map :word rhyming-words)))
         (pprint-table)
         (spit "rhymes.txt")))
  (s/syllabify ["IH" "N" "V" "AA" "L" "V" "Z"])
  (s/syllabify ["D" "EH" "B" "Y" "AH"])

  (s/syllabify ["R" "AW" "N" "D" "M" "IY" "HH" "AA" "R" "T"])
  ;; => (("R" "AW" "N" "D") ("M" "IY") ("HH" "AA" "R" "T")) 
  (s/syllabify ["P" "AE" "D" "M" "AY"])
  (set/union (rimes words (make-word ["boat" "B" "OW" "T"]))
             (onsets words (make-word ["ballboy" "D" "AH" "L" "B" "OY"]))))

