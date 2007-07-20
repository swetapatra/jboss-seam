package org.jboss.seam.framework;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.faces.model.DataModel;

import org.jboss.seam.annotations.Create;
import org.jboss.seam.annotations.Transactional;
import org.jboss.seam.core.Expressions.ValueExpression;
import org.jboss.seam.faces.DataModels;
import org.jboss.seam.persistence.QueryParser;

/**
 * Base class for components which manage a query
 * result set. This class may be reused by either
 * configuration or extension, and may be bound
 * directly to a view, or accessed by some
 * intermediate Seam component.
 * 
 * @author Gavin King
 *
 */
public abstract class Query<T> 
      extends PersistenceController<T> //TODO: extend MutableController!
{
   private static final Pattern FROM_PATTERN = Pattern.compile("(^|\\s)(from)\\s", Pattern.CASE_INSENSITIVE);
   private static final Pattern ORDER_PATTERN = Pattern.compile("\\s(order)(\\s)+by\\s", Pattern.CASE_INSENSITIVE);
   private static final Pattern WHERE_PATTERN = Pattern.compile("\\s(where)\\s", Pattern.CASE_INSENSITIVE);

   private String ejbql;
   private Integer firstResult;
   private Integer maxResults;
   private List<String> restrictions = new ArrayList<String>(0);
   private String order;
   
   private DataModel dataModel;
   
   private String parsedEjbql;
   private List<ValueExpression> queryParameters;
   private List<String> parsedRestrictions;
   private List<ValueExpression> restrictionParameters;
   
   private List<Object> queryParameterValues;
   private List<Object> restrictionParameterValues;
   
   public abstract List getResultList();
   public abstract Object getSingleResult();
   public abstract Long getResultCount();

   @Create
   public void validate()
   {
      if ( getEjbql()==null )
      {
         throw new IllegalStateException("ejbql is null");
      }
   }
   
   @Transactional
   public DataModel getDataModel()
   {
      if (dataModel==null)
      {
         dataModel = DataModels.instance().getDataModel(this);
      }
      return dataModel;
   }
   
   public Object getDataModelSelection()
   {
      return getDataModel().getRowData();
   }
   
   public int getDataModelSelectionIndex()
   {
      return getDataModel().getRowIndex();
   }
   
   public void refresh()
   {
      clearDataModel();
   }
   
   @Transactional
   public void last()
   {
      setFirstResult( getLastFirstResult().intValue() );
   }
   
   public void next()
   {
      setFirstResult( getNextFirstResult() );
   }

   public void previous()
   {
      setFirstResult( getPreviousFirstResult() );
   }
   
   public void first()
   {
      setFirstResult(0);
   }
   
   protected void clearDataModel()
   {
      dataModel = null;
   }

   @Transactional
   public Long getLastFirstResult()
   {
      Integer pc = getPageCount();
      return pc==null ? null : ( pc.longValue()-1 ) * getMaxResults();
   }
   
   public int getNextFirstResult()
   {
      Integer fr = getFirstResult();
      return ( fr==null ? 0 : fr ) + getMaxResults();
   }

   public int getPreviousFirstResult()
   {
      Integer fr = getFirstResult();
      Integer mr = getMaxResults();
      return mr >= ( fr==null ? 0 : fr ) ? 
               0 : fr - mr;
   }
   
   @Transactional
   public Integer getPageCount()
   {
      if ( getMaxResults()==null )
      {
         return null;
      }
      else
      {
         int rc = getResultCount().intValue();
         int mr = getMaxResults().intValue();
         int pages = rc / mr;
         return rc % mr == 0 ? pages : pages+1;
      }
   }
          
   
   protected void parseEjbql()
   {
      if (parsedEjbql==null || parsedRestrictions==null)
      {
         QueryParser qp = new QueryParser( getEjbql() );
         queryParameters = qp.getParameterValueBindings();
         parsedEjbql = qp.getEjbql();
         
         List<String> restrictionFragments = getRestrictions();
         parsedRestrictions = new ArrayList<String>( restrictionFragments.size() );
         restrictionParameters = new ArrayList<ValueExpression>( restrictionFragments.size() );         
         for ( String restriction: restrictionFragments )
         {
            QueryParser rqp = new QueryParser( restriction, queryParameters.size() + restrictionParameters.size() );            
            if ( rqp.getParameterValueBindings().size()!=1 ) 
            {
               throw new IllegalArgumentException("there should be exactly one value binding in a restriction: " + restriction);
            }            
            parsedRestrictions.add( rqp.getEjbql() );
            restrictionParameters.addAll( rqp.getParameterValueBindings() );
         }
         
      }
   }
   
   protected String getRenderedEjbql()
   {
      StringBuilder builder = new StringBuilder().append(parsedEjbql);
      
      for (int i=0; i<getRestrictions().size(); i++)
      {
         Object parameterValue = restrictionParameters.get(i).getValue();
         if ( isRestrictionParameterSet(parameterValue) )
         {
            if ( WHERE_PATTERN.matcher(builder).find() )
            {
               builder.append(" and ");
            }
            else
            {
               builder.append(" where ");
            }
            builder.append( parsedRestrictions.get(i) );
         }
      }
         
      if ( getOrder()!=null ) builder.append(" order by ").append( getOrder() );
      
      return builder.toString();
   }
   
   protected boolean isRestrictionParameterSet(Object parameterValue)
   {
      return parameterValue!=null && !"".equals(parameterValue);
   }

   protected String getCountEjbql()
   {
      String ejbql = getRenderedEjbql();
      
      Matcher fromMatcher = FROM_PATTERN.matcher(ejbql);
      if ( !fromMatcher.find() )
      {
         throw new IllegalArgumentException("no from clause found in query");
      }
      int fromLoc = fromMatcher.start(2);
      
      Matcher orderMatcher = ORDER_PATTERN.matcher(ejbql);
      int orderLoc = orderMatcher.find() ? orderMatcher.start(1) : ejbql.length();

      return "select count(*) " + ejbql.substring(fromLoc, orderLoc);
   }
   
   public String getEjbql()
   {
      return ejbql;
   }

   public void setEjbql(String ejbql)
   {
      this.ejbql = ejbql;
      parsedEjbql = null;
      refresh();
   }

   public Integer getFirstResult()
   {
      return firstResult;
   }
   
   public boolean isPreviousExists()
   {
      return getFirstResult()!=null && getFirstResult()!=0;
   }

   public abstract boolean isNextExists();

   public void setFirstResult(Integer firstResult)
   {
      this.firstResult = firstResult;
      refresh();
   }

   public Integer getMaxResults()
   {
      return maxResults;
   }

   public void setMaxResults(Integer maxResults)
   {
      this.maxResults = maxResults;
      refresh();
   }

   public List<String> getRestrictions()
   {
      return restrictions;
   }

   public void setRestrictions(List<String> restrictions)
   {
      this.restrictions = restrictions;
      parsedRestrictions = null;
      refresh();
   }

   public String getOrder()
   {
      return order;
   }

   public void setOrder(String order)
   {
      this.order = order;
      refresh();
   }
   
   protected List<ValueExpression> getQueryParameters()
   {
      return queryParameters;
   }
   
   protected List<ValueExpression> getRestrictionParameters()
   {
      return restrictionParameters;
   }
   
   private static boolean isAnyParameterDirty(List<ValueExpression> valueBindings, List<Object> lastParameterValues)
   {
      if (lastParameterValues==null) return true;
      for (int i=0; i<valueBindings.size(); i++)
      {
         Object parameterValue = valueBindings.get(i).getValue();
         Object lastParameterValue = lastParameterValues.get(i);
         //treat empty strings as null, for consistency with isRestrictionParameterSet()
         if ( "".equals(parameterValue) ) parameterValue = null;
         if ( "".equals(lastParameterValue) ) lastParameterValue = null;
         if ( parameterValue!=lastParameterValue && ( parameterValue==null || !parameterValue.equals(lastParameterValue) ) )
         {
            return true;
         }
      }
      return false;
   }
   
   private static List<Object> getParameterValues(List<ValueExpression> valueBindings)
   {
      List<Object> values = new ArrayList<Object>( valueBindings.size() );
      for (int i=0; i<valueBindings.size(); i++)
      {
         values.add( valueBindings.get(i).getValue() );
      }
      return values;
   }
   
   protected void evaluateAllParameters()
   {
      setQueryParameterValues( getParameterValues( getQueryParameters() ) );
      setRestrictionParameterValues( getParameterValues( getRestrictionParameters() ) );
   }
   
   protected boolean isAnyParameterDirty()
   {
      return isAnyParameterDirty( getQueryParameters(), getQueryParameterValues() )
            || isAnyParameterDirty( getRestrictionParameters(), getRestrictionParameterValues() );
   }
   
   protected List<Object> getQueryParameterValues()
   {
      return queryParameterValues;
   }
   
   protected void setQueryParameterValues(List<Object> queryParameterValues)
   {
      this.queryParameterValues = queryParameterValues;
   }
   
   protected List<Object> getRestrictionParameterValues()
   {
      return restrictionParameterValues;
   }
   
   protected void setRestrictionParameterValues(List<Object> restrictionParameterValues)
   {
      this.restrictionParameterValues = restrictionParameterValues;
   }
   protected List truncResultList(List results)
   {
      Integer mr = getMaxResults();
      if ( mr!=null && results.size() > mr )
      {
         return results.subList(0, mr);
      }
      else
      {
         return results;
      }
   }

}
