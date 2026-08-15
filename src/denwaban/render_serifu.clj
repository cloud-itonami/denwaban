(ns denwaban.render-serifu
  "Render the receptionist's closed set of lines ahead of time.

  `clojure -M:serifu [dir] [voice]`

  Run at build time, not on a call. Every line the dialog can say becomes an
  `ulaw@8000` mono file — the carrier's own format, so nothing is converted while
  somebody is waiting.

  Re-runnable: a line whose file already exists is skipped, and because the file
  name is content-addressed, a reworded line renders to a NEW name rather than
  reusing a stale recording. Deleting the directory is always safe."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [denwaban.serifu :as serifu]))

(defn -main [& [dir voice]]
  (let [dir (or dir "resources/serifu")
        entries (serifu/manifest {:dir dir :voice voice})]
    (.mkdirs (io/file dir))
    (doseq [{:serifu/keys [path text command locale]} entries]
      (if (.exists (io/file path))
        (println "skip  " path)
        (let [{:keys [exit err]} (apply shell/sh command)]
          (if (zero? exit)
            (println "render" (name locale) path (str "\"" text "\""))
            (do (println "FAILED" path err)
                (System/exit 1))))))
    (println (count entries) "lines in" dir)))
