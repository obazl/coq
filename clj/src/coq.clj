(ns coq
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.spec.alpha :as spec]
            [clojure.string :as string]
            ;; [clojure.tools.cli :refer [parse-opts]]
            [cli-matic.core :refer [run-cmd]]
            [cuerdas.core :as s]
            [expound.alpha :as expound]
            ;; [instaparse.core :as insta]
            [me.raynes.fs :as fs]
            [stencil.core :as stencil]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]
            [coq.options :as options]
            ;; [obazl.templates.rules :as t]
            [coq.utils :as u]
            ;; [opam.core :as opam]
            )
  (:import (java.net InetAddress)))

(def dep-tree (atom {}))

(def packages (atom {}))

(defn make-deps [pkg deps]
  (let [dep-targets (map (fn [dep]
                           (let [plugin (some #{".cmxs" ".cmo"} [(fs/extension dep)])
                                 tgt (fs/base-name dep)
                                 pkg-path (s/strip-suffix dep (str "/" tgt))
                                 tgt (fs/name tgt)
                                 private (= pkg pkg-path)
                                 label (if private
                                         (str ":" tgt)
                                         (str "//" pkg-path ":"
                                              (if plugin
                                                tgt ;; (s/capital tgt)
                                                tgt)))]
                             (if plugin
                               {:ocamldep label}
                               {:dep label})))
                         deps)
        ;; _ (println (str "MAKEDEPS: " (seq dep-targets)))
        deps (filter :dep dep-targets)
        ;; _ (println (str "DEPS: " (seq deps)))
        ocamldeps (filter :ocamldep dep-targets)
        ;; _ (println (str "OCAMLDEPS: " (seq ocamldeps)))
        ]
    [deps ocamldeps]))

(defn update-modules-list [modules pkg tgt src vdeps viodeps ocamldeps]
  ;; (println (str "UPDATE-MODULES-LIST: " pkg " " tgt))
  ;; (println "MODULES: " modules)
  (let [m (some (fn [module] (if (= tgt (:target module)) module)) modules)
        mm (assoc m :ocamldeps ocamldeps)]
    (if m
      (cons (if (seq vdeps)
              (assoc mm :vdeps vdeps)
              (assoc mm :viodeps viodeps))
            (remove (fn [item] (= tgt (:target item))) modules))
      (cons
       {:target tgt :src src :vdeps vdeps :viodeps viodeps :ocamldeps ocamldeps}
       modules))))

(defn process-deps [line]
  (let [[dependers deps] (string/split line #": *+")
        dependers (string/split dependers #" +")
        deps (string/split deps #" +")
        fst (first deps)
        src (fs/base-name fst)
        tgt (fs/name fst)
        pkg (s/strip-suffix fst (str "/" src))
        [vdeps v_ocamldeps] (if (= ".vo" (fs/extension (first dependers)))
                (make-deps pkg (rest deps)))
        [viodeps vio_ocamldeps] (if (= ".vio" (fs/extension (first dependers)))
                  (make-deps pkg (rest deps)))
        ;; _ (println (str "V_OCAMLDEPS: " (seq v_ocamldeps)))
        ;; _ (println (str "VIO_OCAMLDEPS: " (seq vio_ocamldeps)))
        ocamldeps (into [] (set (concat v_ocamldeps vio_ocamldeps)))
        ]
    ;; (println (str ">>>" line))
    ;; (println (str "first dependers: " (fs/extension (first dependers))))
    ;; (println (str "rest deps" (rest deps)))
    ;; (println (str "vdeps: " (seq vdeps)))
    ;; (println (str "viodeps: " (seq viodeps)))
    (println (str "ocamldeps: " (seq ocamldeps)))
    #_(println (string/join " "
                            [line
                             \newline\tab
                             pkg
                             \newline\tab
                             tgt
                             \newline]
                            ))
    (swap! packages (fn [old-list]
                      (let [old-pkg (get old-list pkg)]
                        (assoc old-list pkg
                               {:modules
                                (update-modules-list
                                 (:modules old-pkg)
                                 pkg tgt src vdeps viodeps ocamldeps)
                                }))))
    ))

(defn process-file-by-lines
  "Process file reading it line-by-line"
  ([file]
   (process-file-by-lines file identity))
  ([file process-fn]
   (process-file-by-lines file process-fn println))
  ([file process-fn output-fn]
   (with-open [rdr (clojure.java.io/reader file)]
     (doseq [line (line-seq rdr)]
       ;; (output-fn
        (process-fn line)
        ;; )
     ))))

(defn theories
  "Manipulate cache."
  [{:keys [coqdeps recursive debug verbose] :as args}]
  (if debug (reset! u/debug true))
  (if verbose (reset! u/verbose true))
  (timbre/info "HELLO")
  (process-file-by-lines coqdeps process-deps)
  ;; (println @packages)
  ;; (pp/pprint (sort (keys @packages)))
  (doseq [[pkg data] @packages]
    (let [build-file (str pkg "/BUILD.bazel")
          ;; _ (println data)
          sorted-modules (sort-by :target (:modules data))]
      (with-open [port (io/writer build-file)]
        (.write port (stencil/render-file
                      "coq/templates/BUILD.bazel.mustache"
                      {:modules sorted-modules})))))

  (shutdown-agents))

(def CONFIGURATION
  {:app         {:command     "coq"
                 :description "A tool for adding Bazel support to Coq projects."
                 :version     "0.0.1"
                 }
   :global-opts [{:option "edn" :short "e"
                  :as "coq.edn config file"
                  :type :string
                  :default "bzl/coq.edn"}]
   :commands    [{:command     "theories"
                  :runs        theories
                  :description ["Generate BUILD.bazel files for theory libraries."]
                  :examples    ["coq theories theories/"],
                  :opts        [{:option "coqdeps" :short "d"
                                 :as "Name of file containing output of coqdep command."
                                 :required true
                                 :type :string}
                                {:option "recursive" :short "r"
                                 :as "Process recursively"
                                 :type :with-flag
                                 :default false}
                                options/verbose
                                options/debug]
                  }
                 ]
   }
  )

(def timbre-config
  {;; :level :debug  ; e/o #{:trace :debug :info :warn :error :fatal :report}
   ;; Control log filtering by namespaces/patterns. Useful for turning off
   ;; logging in noisy libraries, etc.:
   ;; :ns-whitelist  [] #_["my-app.foo-ns"]
   ;; :ns-blacklist  [] #_["taoensso.*"]

   :appenders
   {;; The standard println appender:
    :println (appenders/println-appender {:async? false})}
   })

(defn -main
  "This is our entry point.
  Just pass parameters and configuration.
  Commands (functions) will be invoked as appropriate."
  [& args]
  (timbre/merge-config!
   {:appenders timbre-config})
  (run-cmd args CONFIGURATION))
