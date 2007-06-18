//$Id$
package org.jboss.seam.core;

import static org.jboss.seam.InterceptionType.NEVER;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionEvent;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;

import org.jboss.seam.Component;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.Create;
import org.jboss.seam.annotations.Destroy;
import org.jboss.seam.annotations.FlushModeType;
import org.jboss.seam.annotations.Install;
import org.jboss.seam.annotations.Intercept;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.annotations.Unwrap;
import org.jboss.seam.contexts.Contexts;
import org.jboss.seam.contexts.Lifecycle;
import org.jboss.seam.core.Expressions.ValueExpression;
import org.jboss.seam.log.LogProvider;
import org.jboss.seam.log.Logging;
import org.jboss.seam.persistence.EntityManagerProxy;
import org.jboss.seam.persistence.PersistenceProvider;
import org.jboss.seam.transaction.Transaction;
import org.jboss.seam.util.Naming;

/**
 * A Seam component that manages a conversation-scoped extended
 * persistence context that can be shared by arbitrary other
 * components.
 * 
 * @author Gavin King
 */
@Scope(ScopeType.CONVERSATION)
@Intercept(NEVER)
@Install(false)
public class ManagedPersistenceContext 
   implements Serializable, HttpSessionActivationListener, Mutable, PersistenceContextManager, Synchronization
{
   private static final long serialVersionUID = -4972387440275848126L;
   private static final LogProvider log = Logging.getLogProvider(ManagedPersistenceContext.class);
   
   private EntityManager entityManager;
   private String persistenceUnitJndiName;
   private String componentName;
   private ValueExpression<EntityManagerFactory> entityManagerFactory;
   private List<Filter> filters = new ArrayList<Filter>(0);
   
   private transient boolean synchronizationRegistered;
  
   public boolean clearDirty()
   {
      return true;
   }
   
   @Create
   public void create(Component component)
   {
      this.componentName = component.getName();
      if (persistenceUnitJndiName==null)
      {
         persistenceUnitJndiName = "java:/" + componentName;
      }
      
      PersistenceContexts.instance().touch(componentName);      
   }
   
   private void initEntityManager()
   {
      entityManager = getEntityManagerFactoryFromJndiOrValueBinding().createEntityManager();
      entityManager = new EntityManagerProxy(entityManager);
      setEntityManagerFlushMode( PersistenceContexts.instance().getFlushMode() );

      for (Filter f: filters)
      {
         if ( f.isFilterEnabled() )
         {
            PersistenceProvider.instance().enableFilter(f, entityManager);
         }
      }

      if ( log.isDebugEnabled() )
      {
         if (entityManagerFactory==null)
         {
            log.debug("created seam managed persistence context for persistence unit: "+ persistenceUnitJndiName);
         }
         else 
         {
            log.debug("created seam managed persistence context from EntityManagerFactory");
         }
      }
   }
   
   @Unwrap
   public EntityManager getEntityManager() throws NamingException, SystemException
   {
      if (entityManager==null) initEntityManager();
      
      //join the transaction
      if ( !synchronizationRegistered && !Lifecycle.isDestroying() && Transaction.instance().isActive() )
      {
         LocalTransactionListener transactionListener = TransactionListener.instance();
         if (transactionListener!=null)
         {
            transactionListener.registerSynchronization(this);
            synchronizationRegistered = true;
         }
         entityManager.joinTransaction();
      }
      
      return entityManager;
   }
   
   //we can't use @PrePassivate because it is intercept NEVER
   public void sessionWillPassivate(HttpSessionEvent event)
   {
      //need to create a context, because this can get called
      //outside the JSF request, and we want to use the
      //PersistenceProvider object
      boolean createContext = !Contexts.isApplicationContextActive();
      if (createContext) Lifecycle.beginCall();
      try
      {
         if ( entityManager!=null && !PersistenceProvider.instance().isDirty(entityManager) )
         {
            entityManager.close();
            entityManager = null;
         }
      }
      finally
      {
         if (createContext) Lifecycle.endCall();
      }
   }
   
   //we can't use @PostActivate because it is intercept NEVER
   public void sessionDidActivate(HttpSessionEvent event) {}
   
   @Destroy
   public void destroy()
   {
      if ( !synchronizationRegistered )
      {
         //in requests that come through SeamPhaseListener,
         //there can be multiple transactions per request,
         //but they are all completed by the time contexts
         //are destroyed
         //so wait until the end of the request to close
         //the session
         //on the other hand, if we are still waiting for
         //the transaction to commit, leave it open
         close();
      }
      PersistenceContexts.instance().untouch(componentName);
   }

   public void afterCompletion(int status)
   {
      synchronizationRegistered = false;
      if ( !Contexts.isConversationContextActive() )
      {
         //in calls to MDBs and remote calls to SBs, the 
         //transaction doesn't commit until after contexts
         //are destroyed, so wait until the transaction
         //completes before closing the session
         //on the other hand, if we still have an active
         //conversation context, leave it open
         close();
      }
   }
   
   public void beforeCompletion() {}
   
   private void close()
   {
      if ( log.isDebugEnabled() )
      {
         log.debug("destroying seam managed persistence context for persistence unit: " + persistenceUnitJndiName);
      }
      
      if (entityManager!=null)
      {
         entityManager.close();
      }
   }
   
   public EntityManagerFactory getEntityManagerFactoryFromJndiOrValueBinding()
   {
      EntityManagerFactory result = null;
      //first try to find it via the value binding
      if (entityManagerFactory!=null)
      {
         result = entityManagerFactory.getValue();
      }
      //if its not there, try JNDI
      if (result==null)
      {
         try
         {
            result = (EntityManagerFactory) Naming.getInitialContext().lookup(persistenceUnitJndiName);
         }
         catch (NamingException ne)
         {
            throw new IllegalArgumentException("EntityManagerFactory not found in JNDI", ne);
         }
      }
      return result;
   }
   
   /**
    * A value binding expression that returns an EntityManagerFactory,
    * for use of JPA outside of Java EE 5 / Embeddable EJB3.
    */
   public ValueExpression<EntityManagerFactory> getEntityManagerFactory()
   {
      return entityManagerFactory;
   }
   
   public void setEntityManagerFactory(ValueExpression<EntityManagerFactory> entityManagerFactory)
   {
      this.entityManagerFactory = entityManagerFactory;
   }
   
   /**
    * The JNDI name of the EntityManagerFactory, for 
    * use of JPA in Java EE 5 / Embeddable EJB3.
    */
   public String getPersistenceUnitJndiName()
   {
      return persistenceUnitJndiName;
   }
   
   public void setPersistenceUnitJndiName(String persistenceUnitName)
   {
      this.persistenceUnitJndiName = persistenceUnitName;
   }
   
   public String getComponentName() 
   {
      return componentName;
   }
   
   /**
    * Hibernate filters to enable automatically
    */
   public List<Filter> getFilters()
   {
      return filters;
   }
   
   public void setFilters(List<Filter> filters)
   {
      this.filters = filters;
   }
   
   public void changeFlushMode(FlushModeType flushMode)
   {
      if (entityManager!=null)
      {
         setEntityManagerFlushMode(flushMode);
      }
   }
   
   protected void setEntityManagerFlushMode(FlushModeType flushMode)
   {
      switch (flushMode)
      {
         case AUTO:
            entityManager.setFlushMode(javax.persistence.FlushModeType.AUTO);
            break;
         case COMMIT:
            entityManager.setFlushMode(javax.persistence.FlushModeType.COMMIT);
            break;
         case MANUAL:
            PersistenceProvider.instance().setFlushModeManual(entityManager);
            break;
      }
   }
   @Override
   public String toString()
   {
      return "ManagedPersistenceContext(" + persistenceUnitJndiName + ")";
   }
}
