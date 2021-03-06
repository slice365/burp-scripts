
(ns nrepl
  (:require [burp-clj.extender :as extender]
            [burp-clj.extension-state :refer [make-unload-callback]]
            [burp-clj.scripts :as scripts]
            [burp-clj.state :as state]
            [burp-clj.utils :as utils]
            [burp-clj.validate :as validate]
            [burp-clj.helper :as helper]
            [seesaw.core :as gui]
            [seesaw.bind :as bind]
            [seesaw.border :as border]
            [seesaw.mig :refer [mig-panel]]
            [taoensso.timbre :as log]))

(extender/defsetting :nrepl-server/port 2233 validate/valid-port?)
(extender/defsetting :nrepl/nrepl-version "0.8.2")
(extender/defsetting :nrepl/refactor-version "2.5.0")
(extender/defsetting :nrepl/cider-version "0.25.3")

(defn load-deps
  []
  (helper/add-dep-with-proxy [['nrepl (get-nrepl-version)]
                              ['refactor-nrepl (get-refactor-version)]
                              ['cider/cider-nrepl (get-cider-version)]]))

(defn started?
  []
  (-> (:nrepl-server @state/state)
      boolean))

(defn stop-nrepl
  []
  (when-let [server (:nrepl-server @state/state)]
   ((utils/dyn-call nrepl.server/stop-server) server)
    (swap! state/state dissoc :nrepl-server)
    (log/info "nrepl stopped!")))

(defn wrap-classloader
  [h]
  (fn [msg]
    (utils/ensure-dynamic-classloader)
    (h msg)))

(defn start-nrepl
  []
  (when-not (started?)
    (helper/with-exception-default
      nil
      (load-deps)
      ;; (log/info :start-nrepl :refactor-nrepl-version
      ;;           ((utils/dyn-call refactor-nrepl.core/version)))
      (let [port (get-port)
            _ (log/info "nrepl starting at:" port )
            cider-nrepl-handler (utils/dyn-call cider.nrepl/cider-nrepl-handler)
            wrap-refactor (utils/dyn-call refactor-nrepl.middleware/wrap-refactor)
            start-server (utils/dyn-call nrepl.server/start-server)
            nrepl-server (start-server
                          :bind "0.0.0.0"
                          :port port
                          :handler (-> cider-nrepl-handler
                                       wrap-refactor
                                       wrap-classloader))]
        (swap! state/state assoc :nrepl-server nrepl-server)
        (log/info "nrepl started.")))))

;;;;; gui
(defn make-nrepl-view
  []
  (let [nrepl-port (gui/text :text (str (get-port)))
        get-nrepl-btn-txt (fn [started]
                            (if started
                              "stop nREPL"
                              "start nREPL"))
        nrepl-start-stop-btn (gui/button
                              :text (-> (started?)
                                        get-nrepl-btn-txt)
                              :id :nrepl-start-stop)
        check-set-nrepl-port (fn []
                               (let [port (gui/text nrepl-port)]
                                 (try
                                   (->> port
                                        Integer/parseInt
                                        set-port!)
                                   true
                                   (catch Exception e
                                     (gui/alert e
                                                (str "not valid port: " port)
                                                :type :error)
                                     (gui/invoke-later
                                      (gui/text! nrepl-port (str (get-port))))
                                     false))))]
    (bind/bind
     state/state
     (bind/transform #(-> (:nrepl-server %)
                          get-nrepl-btn-txt))
     (bind/property nrepl-start-stop-btn :text))
    (gui/listen nrepl-start-stop-btn
                :action (fn [e]
                          (when (check-set-nrepl-port)
                            (if (:nrepl-server @state/state)
                              (stop-nrepl)
                              (do (gui/config! e :enabled? false)
                                  (future (start-nrepl)
                                          (gui/invoke-later
                                           (gui/config! e :enabled? true))))))))

    (mig-panel
     :border (border/empty-border :left 10 :top 10)
     :items [
             [(gui/checkbox
               :text "start nrepl server on extension load"
               :selected? (extender/get-setting :nrepl/start-on-load)
               :listen [:selection
                        (fn [e]
                          (->> (gui/selection e)
                               (extender/set-setting! :nrepl/start-on-load)))])
              "span, grow, wrap"]

             ["nrepl version:"]
             [(gui/text :text (get-nrepl-version)
                        :listen [:document
                                 #(-> (gui/text %)
                                      set-nrepl-version!)])
              "wrap, grow"]

             ["cider-nrepl version:"]
             [(gui/text :text (get-cider-version)
                        :listen [:document
                                 #(-> (gui/text %)
                                      set-cider-version!)])
              "wrap, grow"]

             ["refactor-nrepl version:"]
             [(gui/text :text (get-refactor-version)
                        :listen [:document
                                 #(-> (gui/text %)
                                      set-refactor-version!)])
              "wrap, grow"]

             ["server port:"]
             [nrepl-port "wrap, grow, wmin 250,"]

             [nrepl-start-stop-btn "span, grow"]])))


(def reg (scripts/reg-script! :nrepl-server
                              {:name "clojure nrepl server"
                               :version "0.0.1"
                               :min-burp-clj-version "0.1.1"

                               ;; 启用脚本时执行
                               :enable-callback (fn [_]
                                                  (when (extender/get-setting :nrepl/start-on-load)
                                                    (start-nrepl)))

                               ;; 禁用脚本时执行
                               :disable-callback (fn [_] (stop-nrepl))

                               ;; 添加tab
                               :tab {:nrepl-main ;; tab的key,必须全局唯一

                                     {;; 标题
                                      :captain "nREPL"

                                      ;; gui视图
                                      :view (make-nrepl-view)}

                                     ;; 可以添加更多的tab,使用不同的key
                                     }
                               }))
