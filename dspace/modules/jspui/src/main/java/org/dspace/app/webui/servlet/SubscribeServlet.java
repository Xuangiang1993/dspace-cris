/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.webui.servlet;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.dspace.app.cris.service.ApplicationService;
import org.dspace.app.cris.service.CrisSubscribeService;
import org.dspace.app.cris.util.Researcher;
import org.dspace.app.webui.util.JSPManager;
import org.dspace.app.webui.util.UIUtil;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.service.SubscribeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * Servlet for constructing the components of the "My DSpace" page, based on original servlet by Robert Tansley
 * 
 * @author pascarelli 
 * 
 */
public class SubscribeServlet extends DSpaceServlet
{
    /** Logger */
    private static Logger log = Logger.getLogger(SubscribeServlet.class);

    @Autowired
    private SubscribeService subscribeService;
    
    protected void doDSGet(Context context, HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException,
            SQLException, AuthorizeException
    {
        // Simply show list of subscriptions
        showSubscriptions(context, request, response, false);
    }

    protected void doDSPost(Context context, HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException,
            SQLException, AuthorizeException
    {
        ServletContext ctx = getServletContext();
        WebApplicationContext applicationContext = WebApplicationContextUtils
                .getWebApplicationContext(ctx);

        CrisSubscribeService rpsubscribe = (CrisSubscribeService) applicationContext
                .getBean("CrisSubscribeService");
        /*
         * Parameters: submit_unsubscribe - unsubscribe from a collection
         * submit_clear - clear all subscriptions submit_cancel - cancel update -
         * go to My DSpace.
         */
        String submit = UIUtil.getSubmitButton(request, "submit");
        EPerson e = context.getCurrentUser();

        if (submit.equals("submit_clear_comm"))
        {
            // unsubscribe user from everything
        	subscribeService.unsubscribeCommunity(context, e, null);

            // Show the list of subscriptions
            showSubscriptions(context, request, response, true);

            context.complete();
        }
        else if (submit.equals("submit_clear_coll"))
        {
            // unsubscribe user from everything
            subscribeService.unsubscribeCollection(context, e, null);

            // Show the list of subscriptions
            showSubscriptions(context, request, response, true);

            context.complete();
        }
        else if (submit.equals("submit_clear_rp"))
        {
            // unsubscribe user from everything
            rpsubscribe.clearAll(e);

            // Show the list of subscriptions
            showSubscriptions(context, request, response, true);

            context.complete();
        }
        else if (submit.equals("submit_unsubscribe"))
        {
            UUID collID = UIUtil.getUUIDParameter(request, "collection");
            UUID commID = UIUtil.getUUIDParameter(request, "community");
            String crisobjectUUID = request.getParameter("crisobject");
            
            if (collID != null)
            {
                Collection c = ContentServiceFactory.getInstance().getCollectionService().find(context, collID);

                // Sanity check - ignore duff values
                if (c != null)
                {
                    subscribeService.unsubscribe(context, e, c);
                }
            }
            if (commID != null)
            {
                Community c = ContentServiceFactory.getInstance().getCommunityService().find(context, commID);

                // Sanity check - ignore duff values
                if (c != null)
                {
                    subscribeService.unsubscribe(context, e, c);
                }
            }
            if (crisobjectUUID != null && !crisobjectUUID.isEmpty())
            {
                rpsubscribe.unsubscribe(e, crisobjectUUID);
            }
            // Show the list of subscriptions
            showSubscriptions(context, request, response, true);

            context.complete();
        }
        else
        {
            // Back to "My DSpace"
            response.sendRedirect(response.encodeRedirectURL(request
                    .getContextPath()
                    + "/mydspace"));
        }
    }

    /**
     * Show the list of subscriptions
     * 
     * @param context
     *            DSpace context
     * @param request
     *            HTTP request
     * @param response
     *            HTTP response
     * @param updated
     *            if <code>true</code>, write a message indicating that
     *            updated subscriptions have been stored
     */
    private void showSubscriptions(Context context, HttpServletRequest request,
            HttpServletResponse response, boolean updated)
            throws ServletException, IOException, SQLException
    {

        Researcher researcher = new Researcher();
        CrisSubscribeService rpsubscribe = researcher.getCrisSubscribeService();
        ApplicationService applicationService = researcher.getApplicationService();
           
        // Subscribed collections
        List<Collection> subs = subscribeService.getSubscriptionsCollection(context, context
                .getCurrentUser());
        // Subscribed communities
        List<Community> subsComm = subscribeService.getSubscriptionsCommunity(context, context
                .getCurrentUser());
        
        List<String> subsRP = rpsubscribe.getSubscriptions(context
                .getCurrentUser());
        
        request.setAttribute("subscriptions", subs);
        request.setAttribute("comm_subscriptions", subsComm);
        request.setAttribute("crisobject_subscriptions", applicationService.getListByUUIDs(subsRP));
        request.setAttribute("updated", Boolean.valueOf(updated));

        JSPManager.showJSP(request, response, "/mydspace/subscriptions.jsp");
    }

	public SubscribeService getSubscribeService() {
		return subscribeService;
	}

	public void setSubscribeService(SubscribeService subscribeService) {
		this.subscribeService = subscribeService;
	}
}