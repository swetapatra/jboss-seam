package ${packageName};

import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Create;
import org.jboss.seam.annotations.Begin;
import org.jboss.seam.annotations.RequestParameter;
import org.jboss.seam.framework.EntityHome;

import ${entityPackage}.${actionName};

@Name("${componentName}Home")
public class ${actionName}Home extends EntityHome<${actionName}>
{

    @RequestParameter 
    Long ${componentName}Id;
    
    @Override
    public Object getId() 
    { 
        if (${componentName}Id==null)
        {
            return super.getId();
        }
        else
        {
            return ${componentName}Id;
        }
    }
    
    @Override @Begin
    public void create() {
        super.create();
    }
 	
}
