(ns coq.utils
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pp]
            [clojure.spec.alpha :as spec]
            [clojure.string :as cljstr]
            [clojure.walk :as walk]
            [cuerdas.core :as s]
            [me.raynes.fs :as fs]
            [clj-uuid :as uuid]
            [expound.alpha :as expound]
            [taoensso.timbre :as timbre
             :refer [log trace info warn error fatal report]]
            [clj-uuid :as uuid]
            ;; [obazl.dune.deps :as deps]
            ;; [obazl.dune.workspaces :as ws]
            [coq.options :as options]
            ))

(def root (str (-> (java.io.File. ".") .getCanonicalPath)))

;; Config vars
(def backup (atom true))
(def debug (atom false))
(def dry-run (atom false))
(def stats (atom false))
(def verbose (atom false))

(def config-pkg-default "//bzl/config")
(def config-pkg (atom nil))

(def config
  ;;(timbre/info "STATIC: config")]
  (if (fs/exists? "bzl/coq.edn")
    (let [source "bzl/coq.edn"
          config-edn (try
                       (with-open [r (io/reader source)]
                         (edn/read (java.io.PushbackReader. r)))
                       (catch java.io.IOException e
                         (timbre/error (s/format "Couldn't open '%s': %s\n" source (.getMessage e)))
                         (flush)
                         (System/exit 1))
                       (catch RuntimeException e
                         (timbre/error (s/format "Error parsing edn file '%s': %s\n" source (.getMessage e)))
                         (flush)
                         (System/exit 1)))]
      ;; (if-let [pkg (-> config-edn :ppx :shared-pkg)]
      ;;   ;; FIXME: fully parse :shared-ppx-pkg val
      ;;   (do
      ;;     ;; (timbre/debug "SHARED-PPX-PKG FROM CONFIG EDN: " pkg)
      ;;     ;; (flush)
      ;;     (reset! options/shared-ppx-pkg pkg)) ;; (s/strip-prefix pkg "//")))
      ;;   (reset! options/shared-ppx-pkg options/shared-ppx-pkg-default))
      (update config-edn :root #(str (fs/expand-home %))))
    (do
      (timbre/warn "No config file")
      (flush)
      {})))

;; cli overrides config file overrides default
(defn set-config-pkg! [cli-arg]
  (if (not= cli-arg config-pkg-default)
    (reset! config-pkg cli-arg)
    (if (not @config-pkg)
      (reset! config-pkg cli-arg))))

 ;; FIXME: slurp returns a string - we're not reading edn
(defn load-edn
  "Load edn from an io/reader source (filename or io/resource)."
  [source]
  (if @verbose (timbre/debug (str "LOAD-EDN: " source)))
  ;; we need to preprocess the source before trying to read it as edn
  (let [src (slurp source)
        ;; _ (timbre/debug "RAW STR: " src)
        edn-str (-> src
                    ;;FIXME: use (->
                    ;; example:  OCAML:${ocaml_version}, in graphql_ppx/src/base/dune
                    (cljstr/replace #" ([A-Z]+):%\{([^\}]+)\}" " {:arg \"$1:$2\"} ")
                    (cljstr/replace #"\$\{([^\}]+)\}" " {:dollar \"$1\"} ")
                    (cljstr/replace #"-w +(@[a-zA-Z0-9-]*)" "{:warnings \"$1\"}")
                    (cljstr/replace #"\%\{([^:]*):([^\}]*)\}" "{:var {:fn $1 :arg $2}}")
                    (cljstr/replace #"\%\{([^\}]*)\}" "{:var \"$1\"}")
                    ;; flags, e.g. (library_flags -linkall)
                    (cljstr/replace #" -([a-zA-Z]) " " {:flag \"$1\"} ")
                    ;; options, e.g. (flags -warn-error -27)
                    (cljstr/replace #" -([0-9]+[0-9-+]*)" "\"-$1\"")
                    (cljstr/replace #"[\s]+\\[\s]+" " :except "))]
    (if (s/includes? edn-str "tuareg")
      (do
        (timbre/warn "Skipping tuareg dunefile: " source)
        {:tuareg edn-str})
      ;; now read the string as edn
      (let [rdr (java.io.PushbackReader. (java.io.StringReader. edn-str))
            sentinel (Object.)]      ; ← or just use ::eof as sentinel
        ;; (println "READING: " rdr)
        (let [edn-objs (take-while #(not= sentinel %)
                                   (repeatedly #(edn/read {:eof sentinel} rdr)))]
          ;; (println (str "EDN-OBJS: " (seq edn-objs)))
          edn-objs)))))

      ;; this method just reads the first edn object from the string:
      #_(try
        (edn/read-string edn-str)
        (catch RuntimeException e
          (printf "Error parsing edn file '%s': %s\n" source (.getMessage e))
          (println edn-str)))


(defn read-depfile [f]
  (with-open [rdr (clojure.java.io/reader f)]
    (reduce conj [] (line-seq rdr))))

(defn make-depline [line]
  (let [tokens (s/split line #" ")
        fname (s/strip-suffix (first tokens) ":")
        deps (into [] (rest tokens))
        module (-> fname fs/base-name fs/split-ext first)] ;; use to gen target label and module on print
    ;; (println (str fname " " module))
    (cons module (cons fname [deps]))))

(defn find-first
         [f coll]
         (filter f coll))

(defn pps-edit
  "A pps sexp may include a file path, e.g. '-schema
  src/app/archive/archive_graphql_schema.json' from
  sr/app/archive/archive_lib/graphql_query/dune. We need to stringify
  it."
  ;;FIXME: better solution is to deal with arg following -schema
  [pps]
  (println "PPS-EDIT  : " pps)
  (let [result (loop [result []
                      pps pps]
                 (if (empty? pps)
                   result
                   (let [hd (first pps)
                         hd (if (s/includes? (str hd) "/")
                              ;; or: ends-with? ".json" etc.
                              (str "\"" hd "\"")
                              hd)]
                     (recur (conj result hd)
                            (next pps)))))]
    ;; (println "pps-edited: " result)
    result))

  ;; (let [modnames (sort (map (fn [depline]
  ;;                             (-> depline first name (str "_cm") keyword))
  ;;                           deplines))]

(defn capitalize-initial-char [word]
  (let [word (str word)]
    (str (s/capital (s/slice word 0 1))
         (s/slice word 1))))

(defn list-diff [l1 l2]
  (mapcat
    (fn [[x n]] (repeat n x))
    (apply merge-with - (map frequencies [l1 l2]))))

(defn add-last-tag [mapseq]
  ;; (timbre/debug "ADD-LAST-TAG: " (seq mapseq))
  (let [mapseq (vec mapseq)]
    (if (empty? mapseq)
      mapseq
      (conj (pop mapseq) (assoc (last mapseq) :last true)))))

(defn deps-sorter [a b]
  "Comparator to put deps in dependency-order."
  ;; (if @verbose (info (str "DEPS-SORTER: " a " - " b)))
  (let [akey (symbol (first a))
        adeps (set (second a))
        bkey (symbol (first b))
        bdeps (set (second b))
        ;; _ (println (str "BDEPS: " bdeps))
        result (if (bdeps akey)
                 -1
                 (if (adeps bkey)
                   1
                   0))]
    ;; (if @verbose (info (str "DEPS-SORTER RESULT: " result)))
    result))

  ;;                        ;; consolidate module impl and intf depmaps
  ;; depmap (vals (reduce
  ;;               (fn [deps-map depmap]
  ;;                 (let [module (:module depmap)]
  ;;                   (if (deps-map module)
  ;;                     (update deps-map module (fn [old] (merge old depmap)))
  ;;                     (assoc deps-map module depmap))))
  ;;               {} ;; deps-map
  ;;               depsmaps)))


;; (defn Xpath->fq-label [s]
;;   "Path to fully-qualified label"
;;   (let [ss (keys @ws/workspaces)
;;         strings (reverse (sort ss))
;;         ws-path (some #(if (s/starts-with? (str s) %)
;;                          % false)
;;                       strings)
;;         ;; need to match both foo/snarky and foo/snarky/bar, but not foo/snarky_bar
;;         fq-label (if (= ws-path s)
;;               (str "@" (:ws (@ws/workspaces ws-path)) "//" (s/strip-prefix s ws-path))
;;               (if (s/starts-with? (str s) (str ws-path "/"))
;;                 (str "@" (:ws (@ws/workspaces ws-path)) "/" (s/strip-prefix s ws-path))
;;                 ;; had a false match, e.g. ws foo/snarky matching foo/snarky_bar
;;                 (let [ws-path (some #(if (s/starts-with? (str s) %)
;;                                        % false)
;;                                     (remove #(= ws-path %) strings))]
;;                   (if ws-path
;;                     (str "@" (:ws (@ws/workspaces ws-path)) "/" (s/strip-prefix s ws-path))
;;                     nil))))]
;;     fq-label))

;;FIXME: memoize
;; (defn path->fq-label [s]
;;   "Path to label"
;;   ;; (timbre/debug "PATH->FQ-LABEL: " s)
;;   ;; (pp/pprint @ws/workspaces)
;;   ;; (System/exit 0)
;;   (let [paths (into {} (loop [wss [@ws/workspaces]
;;                                     result []]
;;                                (if (empty? wss)
;;                                  result
;;                                  (recur (if (empty? (:deps (first wss)))
;;                                           (next wss)
;;                                           (concat (:deps (first wss)) (next wss)))
;;                                         (conj result
;;                                               [(:path (first wss))(:ws (first wss))])))))
;;         ;; _ (timbre/debug "SEQ PATHS: " (seq paths))
;;         ws-paths (filter (fn [[p n]]
;;                           ;; (timbre/debug "XXXX p: " p)
;;                           ;; (timbre/debug "XXXX n: " n)
;;                           (s/starts-with? (str s) (str p "/")))
;;                         (seq paths))
;;         ;; _ (do
;;         ;;     (timbre/debug (str "WS-PATHS: "))
;;         ;;     (pp/pprint ws-paths))
;;         ws-path (first (reverse (sort ws-paths)))
;;         ;; _ (timbre/debug (str "WS-PATH: " ws-path))
;;         label (str "@" (second ws-path) "/"
;;                    (s/strip-prefix (str s) (str (first ws-path))))
;;         ;; _ (timbre/debug (str "LABEL: " label))
;;         ;; _ (System/exit 0)
;;         ]
;;     ;; ws
;;     label))

;;FIXME: memoize
(defn keys-in
  "Returns a sequence of all key paths in a given map using DFS walk."
  [m]
  (letfn [(children [node]
            (let [v (get-in m node)]
              (if (map? v)
                (map (fn [x] (conj node x)) (keys v))
                [])))
          (branch? [node] (-> (children node) seq boolean))]
    (->> (keys m)
         (map vector)
         (mapcat #(tree-seq branch? children %)))))

(def singletons (atom #{}))

(defn singleton? [stanza srcs]
  (do
    (timbre/debug (str "SINGLETON? " stanza))
    (timbre/debug (str "SRCS: " (seq srcs))))
  (let [result (if (empty? srcs) ;; e.g. c targets, e.g. digestif/src-c/native
                 false
                 ;; case: one .ml file present
                 (if (= 1 (count (stanza :components)))
                   (if (= (:raw-name (first (stanza :components)))
                          (stanza :name))
                     true
                     false)
                   false))]
    (timbre/debug "SINGLETON? " result)
    result))

      ;; (let [src1-basename (-> srcs first fs/base-name)
      ;;       src1-ext (fs/extension src1-basename)
      ;;       src1-module (s/strip-suffix src1-basename src1-ext)]
      ;;   (println (str "SRC1: " src1-module " stanza name: " (str (or (:name stanza) (:public_name stanza)))))
      ;;   ;; (if (= (str (or (:name stanza) (:public_name stanza))) (str src1-module))
      ;;     (do
      ;;       (timbre/debug "SINGLETON: " (:dunefile stanza) (str (or (:name stanza) (:public_name stanza))))
      ;;       (swap! singletons (fn [old] (conj old {:dunefile (:dunefile stanza)
      ;;                                              :target (str (or (:name stanza) (:public_name stanza)))
      ;;                                              :type (:type stanza)})))
      ;;       true)
      ;;     ;; false)
      ;;     )
      ;; (if (> (count srcs) 2)
      ;;   false
      ;;   ;; case: both .ml and .mli present
      ;;   (let [src1-basename (-> srcs first fs/base-name)
      ;;         src1-ext (fs/extension src1-basename)
      ;;         src1-module (s/strip-suffix src1-basename src1-ext)
      ;;         _ (println (str "SRC1 MODULE: " src1-module))
      ;;         src2-basename (-> srcs second fs/base-name)
      ;;         src2-ext (fs/extension src2-basename)
      ;;         src2-module (s/strip-suffix src2-basename src2-ext)
      ;;         _ (println (str "SRC2 MODULE: " src2-module))
      ;;         singleton (= src1-module src2-module)]
      ;;     (if singleton
      ;;       (if (= src1-module (or (:name stanza) (:public_name stanza)))
      ;;         true
      ;;         false)
      ;;       false))))))

(defn write-buildfile-backup [filename]
  (let [ext (fs/extension filename)
        basename (fs/base-name filename)
        path (fs/parent filename) ;; (fs/parent filename)
        ;; _ (timbre/debug (str "EXT: " ext))
        backups (fs/find-files path (re-pattern (str basename ".~.?.?~")))] ;;  #"BUILD.bazel.~.?.?~")]
    (let [backup-name (if (seq backups)
                        (let [nms (map #(fs/base-name %) backups)
                              buexts (map #(fs/extension %) nms)
                              nbrs (map
                                    #(Integer/parseInt (s/trim (s/ltrim % ".") "~"))
                                    buexts)
                              maxbu (apply max nbrs)
                              backup-name (str basename ".~" (inc maxbu) "~") ;  "BUILD.bazel.~"
                              ]
                          backup-name)
                        (str basename ".~1~"))  ;;  "BUILD.bazel.~1~"
          backup-file (io/file path backup-name)]
      ;; (if @verbose (info (s/format "%s will be backed up to %s" filename backup-file)))
      (if (not @dry-run)
        (io/copy (io/file filename) backup-file)))))

(defn library-modules-filter [filter-spec]
  "Returns a fn that decides if a module is included or excluded based on the (modules ...) clause"
  (let [filter-spec (if (nil? filter-spec)
                      nil
                      (if (sequential? filter-spec)
                        filter-spec
                        (list filter-spec)))] ;
    (if (nil? filter-spec)
      (constantly true) ;; default
      (if (sequential? filter-spec)
        (let [[includes excludes] (split-with (complement #{:except}) filter-spec)]
          #_(do (timbre/debug (str "INCLUDES: " (seq includes)))
              (timbre/debug (str "EXCLUDES: " (seq excludes))))
          (if (= (first includes) :standard)
            (if excludes
              (let [excludes (into #{} (rest excludes))] ;; first elt is :except
                (fn [module]
                  (let [module (symbol (module :raw-name))]
                    (not (excludes module)))))
              (fn [module]
                (constantly true)))
            (let [includes (into #{} includes)]
              ;; (timbre/debug (str "No :standard"))
              (fn [module]
                ;; (timbre/debug "DECIDING " module)
                (includes (symbol (module :raw-name)))))))
        (do ;; e.g. src/lib/syncable_ledger: (modules syncable_ledger)
          (timbre/debug (str "INFO: modules clause not a list"))
          )))))
