(ns coq.options
  (:refer-clojure :exclude [force])
  (:require [expound.alpha :as expound]
            [cuerdas.core :as s]
            [taoensso.timbre :as timbre]))

(def build-bzl (atom false))
(def compile-options (atom []))
(def global-options (atom true))
(def repo-options (atom true))
(def pkg-options (atom false))

(expound/def ::CONFIG-PKG-SPEC
  (fn [config-pkg]
    (and (string? config-pkg)
           (or
            #_(and (s/starts-with? config-pkg "@")
                 (or (re-matches #"@([a-z.]*)?//.*" config-pkg)
                     (and (re-matches #"@[a-z.]*" config-pkg))))
            (and (s/starts-with? config-pkg "//")
                 (re-matches #"//.*" config-pkg))
            #_(and (re-matches #"[a-z.]+/.*" shared-ppx-pkg)))))
  "Pkg must start with @ or //")

(expound/def ::DIR-SPEC
  (fn [dir]
    (and (string? dir)
         (not (s/starts-with? dir "/"))))
  "Dir must be a relative path.")

(def debug
  {:option "debug"
   :as "Debug mode"
   :type :with-flag
   :default false})

(def dir
  {:option "dir" :short 0
   :as "Directory containing a dune file."
   :type :string
   :spec ::DIR-SPEC
   :default :present
   })

(def force
  {:option "force" :short "f"
   :as "Force overwrite of existing BUILD.bazel file."
   :type :with-flag
   :default false})

(def verbose
  {:option "verbose" :short "v"
   :as "Verbose."
   :type :with-flag
   :default false})

