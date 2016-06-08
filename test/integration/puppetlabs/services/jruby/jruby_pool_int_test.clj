(ns puppetlabs.services.jruby.jruby-pool-int-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.services.jruby.jruby-pool-manager-service :refer [jruby-pool-manager-service]]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9]
            [puppetlabs.http.client.sync :as http-client]
            [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.internal :as tk-internal]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.request-handler.request-handler-service :as handler-service]
            [puppetlabs.services.versioned-code-service.versioned-code-service :as vcs]
            [puppetlabs.services.config.puppet-server-config-service :as ps-config]
            [puppetlabs.services.protocols.request-handler :as handler]
            [puppetlabs.services.request-handler.request-handler-core :as handler-core]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [puppetlabs.kitchensink.testutils :as ks-testutils]
            [puppetlabs.puppetserver.testutils :as testutils :refer
             [ca-cert localhost-cert localhost-key ssl-request-options]]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.trapperkeeper.bootstrap :as tk-bootstrap]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/jruby/jruby_pool_int_test")

(use-fixtures :once
              schema-test/validate-schemas
              (testutils/with-puppet-conf (fs/file test-resources-dir "puppet.conf")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def default-borrow-timeout 180000)

(defn timed-deref
  [ref]
  (deref ref 240000 :timed-out))

(defn get-stack-trace-for-thread-as-str
  [stack-trace-elements]
  (reduce
   (fn [acc stack-trace-element]
     (str acc
          "  "
          (.getClassName stack-trace-element)
          "."
          (.getMethodName stack-trace-element)
          "("
          (.getFileName stack-trace-element)
          ":"
          (.getLineNumber stack-trace-element)
          ")"
          "\n"))
   ""
   stack-trace-elements))

(defn get-all-stack-traces-as-str
  []
  (reduce
   (fn [acc thread-stack-element]
     (let [thread (key thread-stack-element)]
       (str acc
            "\""
            (.getName thread)
            "\" id="
            (.getId thread)
            " state="
            (.getState thread)
            "\n"
            (get-stack-trace-for-thread-as-str
             (val thread-stack-element)))))
   ""
   (Thread/getAllStackTraces)))

(def script-to-check-if-constant-is-defined
  "! $instance_id.nil?")

(defn add-watch-for-flush-complete
  [pool-context]
  (let [flush-complete (promise)]
    (add-watch (:pool-agent pool-context) :flush-callback
               (fn [k a old-state new-state]
                 (when (= k :flush-callback)
                   (remove-watch a :flush-callback)
                   (deliver flush-complete true))))
    flush-complete))

(defn set-constants-and-verify
  [pool-context num-instances]
  ;; here we set a variable called 'instance_id' in each instance
  (jruby-testutils/reduce-over-jrubies!
    pool-context
    num-instances
    #(format "$instance_id = %s" %))
  ;; and validate that we can read that value back from each instance
  (= (set (range num-instances))
     (-> (jruby-testutils/reduce-over-jrubies!
           pool-context
           num-instances
           (constantly "$instance_id"))
         set)))

(defn constant-defined?
  [jruby-instance]
  (let [sc (:scripting-container jruby-instance)]
    (.runScriptlet sc script-to-check-if-constant-is-defined)))

(defn check-all-jrubies-for-constants
  [pool-context num-instances]
  (jruby-testutils/reduce-over-jrubies!
    pool-context
    num-instances
    (constantly script-to-check-if-constant-is-defined)))


(defn verify-no-constants
  [pool-context num-instances]
  ;; verify that the constants are cleared out from the instances by looping
  ;; over them and expecting a 'NameError' when we reference the constant by name.
  (every? false? (check-all-jrubies-for-constants pool-context num-instances)))

(defn trigger-flush
  [ssl-options]
  (let [response (http-client/delete
                   "https://localhost:8140/puppet-admin-api/v1/jruby-pool"
                   ssl-options)]
    (= 204 (:status response))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:integration admin-api-flush-jruby-pool-test
  (testing "Flushing the pool results in all new JRuby instances"
    (bootstrap/with-puppetserver-running
      app
      {:jruby-puppet {:max-active-instances 4
                      :borrow-timeout default-borrow-timeout}}
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)]
        ;; set a ruby constant in each instance so that we can recognize them
        (is (true? (set-constants-and-verify pool-context 4)))
        (let [flush-complete (add-watch-for-flush-complete pool-context)]
          (is (true? (trigger-flush ssl-request-options)))
          (is (true? (timed-deref flush-complete))
              (str "timed out waiting for the flush to complete, stack:\n"
                   (get-all-stack-traces-as-str))))
        ;; now the pool is flushed, so the constants should be cleared
        (is (true? (verify-no-constants pool-context 4)))))))

(defprotocol BonusService
  (bonus-service-fn [this]))

(deftest ^:integration test-restart-comes-back
  (testing "After a TK restart puppetserver can still handle requests"
    (let [call-seq (atom [])
          debug-log "./target/test-restart-comes-back.log"
          lc-fn (fn [context action] (swap! call-seq conj action) context)
          bonus-service (tk-services/service BonusService
                          [[:MasterService]]
                          (init [this context] (lc-fn context :init-bonus-service))
                          (start [this context] (lc-fn context :start-bonus-service))
                          (stop [this context] (lc-fn context :stop-bonus-service))
                          (bonus-service-fn [this] (lc-fn nil :bonus-service-fn)))]
      (fs/delete debug-log)
      (bootstrap/with-puppetserver-running-with-services
       app
       (conj (tk-bootstrap/parse-bootstrap-config! bootstrap/dev-bootstrap-file) bonus-service)
       {:global {:logging-config
                 (str "./dev-resources/puppetlabs/services/"
                      "jruby/jruby_pool_int_test/"
                      "logback-test-restart-comes-back.xml")}
        :jruby-puppet {:max-active-instances 1
                       :borrow-timeout default-borrow-timeout}}
       (tk-internal/restart-tk-apps [app])
       (let [start (System/currentTimeMillis)]
         (while (and (not= (count @call-seq) 5)
                     (< (- (System/currentTimeMillis) start) 300000))
           (Thread/yield)))
       (let [shutdown-service (tk-app/get-service app :ShutdownService)]
         (is (nil? (tk-internal/get-shutdown-reason shutdown-service))
             "shutdown reason was unexpectedly set after restart"))
       (is (= @call-seq
              [:init-bonus-service :start-bonus-service :stop-bonus-service :init-bonus-service :start-bonus-service])
           (str "dumping puppetserver.log\n" (slurp debug-log)))
       (let [get-results (http-client/get "https://localhost:8140/puppet/v3/environments"
                                          testutils/catalog-request-options)]
         (is (= 200 (:status get-results))))))))

(deftest ^:integration test-503-when-app-shuts-down
  (testing "During a shutdown the agent requests result in a 503 response"
    (ks-testutils/with-no-jvm-shutdown-hooks
     (let [services [jruby/jruby-puppet-pooled-service profiler/puppet-profiler-service
                     handler-service/request-handler-service ps-config/puppet-server-config-service
                     jetty9/jetty9-service vcs/versioned-code-service jruby-pool-manager-service]
           config (-> (jruby-testutils/jruby-puppet-tk-config
                       (jruby-testutils/jruby-puppet-config {:max-active-instances 2
                                                             :borrow-timeout
                                                             default-borrow-timeout}))
                      (assoc-in [:webserver :port] 8081))
           app (tk/boot-services-with-config services config)
           cert (ssl-utils/pem->cert
                 (str test-resources-dir "/localhost-cert.pem"))
           jruby-service (tk-app/get-service app :JRubyPuppetService)
           jruby-instance (jruby-protocol/borrow-instance jruby-service :i-want-this-instance)
           handler-service (tk-app/get-service app :RequestHandlerService)
           request {:uri "/puppet/v3/environments", :params {}, :headers {},
                    :request-method :GET, :body "", :ssl-client-cert cert, :content-type ""}
           ping-environment #(->> request (handler-core/wrap-params-for-jruby) (handler/handle-request handler-service))
           stop-complete? (future (tk-app/stop app))]
       (let [start (System/currentTimeMillis)]
         (logging/with-test-logging
          (while (and
                  (< (- (System/currentTimeMillis) start) 10000)
                  (not= 503 (:status (ping-environment))))
            (Thread/yield))
          (is (= 503 (:status (ping-environment)))))
         (jruby-protocol/return-instance jruby-service jruby-instance :i-want-this-instance)
         (is (not= :timed-out (timed-deref stop-complete?))
             (str "timed out waiting for the stop to complete, stack:\n"
                  (get-all-stack-traces-as-str)))
         (logging/with-test-logging
          (is (= 503 (:status (ping-environment))))))))))

(deftest ^:integration test-503-when-jruby-is-first-to-shutdown
  (testing "During a shutdown requests result in 503 http responses"
    (bootstrap/with-puppetserver-running
     app
     {:jruby-puppet {:max-active-instances 2
                     :borrow-timeout default-borrow-timeout}}
     (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
           context (tk-services/service-context jruby-service)
           jruby-instance (jruby-protocol/borrow-instance jruby-service :i-want-this-instance)
           stop-complete? (future (tk-services/stop jruby-service context))
           ping-environment #(testutils/http-get "puppet/v3/environments")]
       (logging/with-test-logging
        (let [start (System/currentTimeMillis)]
          (while (and
                  (< (- (System/currentTimeMillis) start) 10000)
                  (not= 503 (:status (ping-environment))))
            (Thread/yield)))
        (is (= 503 (:status (ping-environment)))))
       (jruby-protocol/return-instance jruby-service jruby-instance :i-want-this-instance)
       (is (not= :timed-out (timed-deref stop-complete?))
           (str "timed out waiting for the stop to complete, stack:\n"
                (get-all-stack-traces-as-str)))
       (let [app-context (tk-app/app-context app)]
         ;; We have to re-initialize the JRubyPuppetService here because
         ;; otherwise the tk-app/stop that is included in the
         ;; with-puppetserver-running macro will fail, as the
         ;; JRubyPuppetService is already stopped.
         (swap! app-context assoc-in [:service-contexts :JRubyPuppetService] {})
         (tk-internal/run-lifecycle-fn! app-context tk-services/init "init" :JRubyPuppetService jruby-service)
         (tk-internal/run-lifecycle-fn! app-context tk-services/start "start" :JRubyPuppetService jruby-service))))))
