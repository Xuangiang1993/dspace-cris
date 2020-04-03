package org.dspace.app.cris.integration.orcid;

import org.orcid.jaxb.model.common_v3.Affiliation;

public class WrapperEmployment
{
    Integer id;

    String uuid;

    Integer type;

    Affiliation employment;

    public Integer getId()
    {
        return id;
    }
    public void setId(Integer id)
    {
        this.id = id;
    }
    public String getUuid()
    {
        return uuid;
    }

    public void setUuid(String uuid)
    {
        this.uuid = uuid;
    }

    public Affiliation getEmployment()
    {
        return employment;
    }

    public void setEmployment(Affiliation employment)
    {
        this.employment = employment;
    }
    
    public Integer getType()
    {
        return type;
    }
    public void setType(Integer type)
    {
        this.type = type;
    }
}
