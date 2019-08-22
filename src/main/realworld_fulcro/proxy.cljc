(ns realworld-fulcro.proxy
  (:require [com.wsscode.pathom.core :as p]
            [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
            [com.wsscode.pathom.connect :as pc]
            [com.wsscode.pathom.diplomat.http :as http]
            [camel-snake-kebab.core :as csk]
            #?(:clj  [com.wsscode.pathom.diplomat.http.clj-http :as http.driver]
               :cljs [com.wsscode.pathom.diplomat.http.fetch :as http.driver])
            [clojure.core.async :as async]
            [edn-query-language.core :as eql]))


(defn qualify
  [m ns]
  (let [ns (name ns)]
    (into {} (keep (fn [[k v]]
                     (when-not (nil? v)
                       [(keyword ns (csk/->kebab-case-string k)) v])))
          m)))


(pc/defmutation create-user [{::keys [api-url] :as this} {:app.user/keys [username password email]}]
  {::pc/output [:app.user/id
                :app.user/email
                :app.user/created-at
                :app.user/updated-at
                :app.user/username
                :app.user/bio
                :app.user/image
                :app.user/token]
   ::pc/sym    `app.user/create-user
   ::pc/params [:api.user/username
                :api.user/email
                :api.user/password]}
  (let [response (http/request (assoc this
                                 ::http/url "https://conduit.productionready.io/api/users"
                                 ::http/method ::http/post
                                 ::http/content-type ::http/json
                                 ::http/accept ::http/json
                                 ::http/form-params {:user {:username username
                                                            :email    email
                                                            :password password}}))]
    (async/go
      (let [{::http/keys [body]} (async/<! response)]
        (-> body
            :user
            (qualify :app.user))))))

(pc/defmutation login [{::keys [api-url] :as this} {:app.user/keys [password email]}]
  {::pc/output [:app.user/id
                :app.user/email
                :app.user/created-at
                :app.user/updated-at
                :app.user/username
                :app.user/bio
                :app.user/image
                :app.user/token]
   ::pc/sym    `app.user/login
   ::pc/params [:api.user/email
                :api.user/password]}
  (let [response (http/request (assoc this
                                 ::http/url "https://conduit.productionready.io/api/users/login"
                                 ::http/method ::http/post
                                 ::http/content-type ::http/json
                                 ::http/accept ::http/json
                                 ::http/form-params {:user {:email    email
                                                            :password password}}))]
    (async/go
      (let [{::http/keys [body]} (async/<! response)]
        (-> body
            :user
            (qualify :app.user))))))


(pc/defresolver index-explorer [env _]
  {::pc/input  #{:com.wsscode.pathom.viz.index-explorer/id}
   ::pc/output [:com.wsscode.pathom.viz.index-explorer/index]}
  {:com.wsscode.pathom.viz.index-explorer/index
   (p/transduce-maps
     (remove (comp #{::pc/resolve ::pc/mutate} key))
     (get env ::pc/indexes))})


(def register
  [create-user index-explorer login])

(def parser
  (p/parallel-parser
    {::p/env     {::http/driver            http.driver/request-async
                  ::p/placeholder-prefixes #{">"}
                  ::p/reader               [p/map-reader
                                            pc/all-parallel-readers
                                            p/env-placeholder-reader]}
     ::p/mutate  pc/mutate-async
     ::p/plugins [(pc/connect-plugin {::pc/register register})
                  p/error-handler-plugin
                  p/elide-special-outputs-plugin
                  p/trace-plugin]}))

(def remote
  {:transmit! (fn transmit!
                [_ {::txn/keys [ast result-handler]}]
                (let [ast (update ast :children conj (eql/expr->ast :com.wsscode.pathom/trace))
                      edn (eql/ast->query ast)
                      result (parser {} edn)]
                  (async/go
                    (result-handler {:transaction ast
                                     :body        (async/<! result)
                                     :status-code 200}))))})
