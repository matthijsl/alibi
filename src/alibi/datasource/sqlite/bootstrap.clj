(ns alibi.datasource.sqlite.bootstrap
  (:require
    [alibi.domain.entry.repository :as entry-repo]
    [alibi.domain.project.repository :as project-repo]
    [alibi.domain.task.repository :as task-repo]
    [alibi.domain.query-handler :as queries]
    [alibi.domain.user.repository :as user-repo]
    [alibi.datasource.sqlite.entry-repo :as sqlite-entry-repo]
    [alibi.datasource.sqlite.task-repo :as sqlite-task-repo]
    [alibi.datasource.sqlite.project-repo :as sqlite-project-repo]
    [alibi.datasource.sqlite.queries :as sqlite-queries]
    [alibi.application.alibi-identity :as identity]))

(def user-repo
  (reify
    user-repo/UserRepository
    (-user-exists? [_ _] true)
    identity/Identity
    (-user-id-for-username [_ _] 1)))

(defmacro with-sqlite [db-spec & body]
  `(let [db# ~db-spec]
     (entry-repo/with-impl (sqlite-entry-repo/new db#)
       (project-repo/with-impl (sqlite-project-repo/new db#)
         (task-repo/with-impl (sqlite-task-repo/new db#)
           (user-repo/with-impl user-repo
             (identity/with-impl user-repo
               (queries/with-handler (sqlite-queries/handler db#)
                 ~@body))))))))
