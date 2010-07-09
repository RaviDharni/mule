/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSource, Inc.  All rights reserved.  http://www.mulesource.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.routing.outbound;

import org.mule.DefaultMuleMessage;
import org.mule.api.MessagingException;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.MuleSession;
import org.mule.api.endpoint.OutboundEndpoint;
import org.mule.api.routing.CouldNotRouteOutboundMessageException;
import org.mule.api.routing.RoutePathNotFoundException;
import org.mule.api.routing.RoutingException;
import org.mule.config.i18n.CoreMessages;

import java.util.ArrayList;
import java.util.List;

/**
 * <code>MulticastingRouter</code> will broadcast the current message to every endpoint
 * registed with the router.
 */

public class MulticastingRouter extends FilteringOutboundRouter
{
    @Override
    public MuleEvent route(MuleEvent event) throws RoutingException
    {
        MuleMessage message = event.getMessage();
        MuleSession session = event.getSession();
        if (endpoints == null || endpoints.size() == 0)
        {
            throw new RoutePathNotFoundException(CoreMessages.noEndpointsForRouter(), message, null);
        }
        if (enableCorrelation != ENABLE_CORRELATION_NEVER)
        {
            boolean correlationSet = message.getCorrelationId() != null;
            if (correlationSet && (enableCorrelation == ENABLE_CORRELATION_IF_NOT_SET))
            {
                logger.debug("CorrelationId is already set, not setting Correlation group size");
            }
            else
            {
                // the correlationId will be set by the AbstractOutboundRouter
                message.setCorrelationGroupSize(endpoints.size());
            }
        }

        List<MuleEvent> results = new ArrayList<MuleEvent>(endpoints.size());
        try
        {
            OutboundEndpoint endpoint;
            for (int i = 0; i < endpoints.size(); i++)
            {
                endpoint = endpoints.get(i);
                if(endpoint.getFilter()==null || (endpoint.getFilter()!=null && endpoint.getFilter().accept(message)))
                {
                    if (((DefaultMuleMessage) message).isConsumable())
                    {
                        throw new MessagingException(
                            CoreMessages.cannotCopyStreamPayload(message.getPayload().getClass().getName()),
                            message);
                    }
                    
                    MuleMessage clonedMessage = new DefaultMuleMessage(message.getPayload(), 
                        message, muleContext);
                    if (endpoint.isSynchronous())
                    {
                        results.add(sendRequest(session, clonedMessage, endpoint, true));
                    }
                    else
                    {
                        sendRequest(session, clonedMessage, endpoint, false);
                    }
                }
            }
        }
        catch (MuleException e)
        {
            throw new CouldNotRouteOutboundMessageException(message, endpoints.get(0), e);
        }
        return resultsHandler.aggregateResults(results, muleContext, event);
    }
}
