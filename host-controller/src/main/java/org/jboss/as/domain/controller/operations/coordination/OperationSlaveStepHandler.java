/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.domain.controller.operations.coordination;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OPERATION_HEADERS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.domain.controller.DomainControllerMessages.MESSAGES;

import java.util.Map;

import org.jboss.as.controller.ControllerMessages;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.ProxyController;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.domain.controller.LocalHostControllerInfo;
import org.jboss.as.host.controller.ignored.IgnoredDomainResourceRegistry;
import org.jboss.dmr.ModelNode;

/**
 * Performs the host specific overall execution of an operation on behalf of the domain.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class OperationSlaveStepHandler {

    private final LocalHostControllerInfo localHostControllerInfo;
    private final Map<String, ProxyController> serverProxies;
    private final IgnoredDomainResourceRegistry ignoredDomainResourceRegistry;

    OperationSlaveStepHandler(final LocalHostControllerInfo localHostControllerInfo, Map<String, ProxyController> serverProxies,
                              IgnoredDomainResourceRegistry ignoredDomainResourceRegistry) {
        this.localHostControllerInfo = localHostControllerInfo;
        this.serverProxies = serverProxies;
        this.ignoredDomainResourceRegistry = ignoredDomainResourceRegistry;
    }

    void execute(OperationContext context, ModelNode operation) throws OperationFailedException {

        operation.get(OPERATION_HEADERS).remove(PrepareStepHandler.EXECUTE_FOR_COORDINATOR);

        addSteps(context, operation, null, true);
        context.stepCompleted();
    }

    void addSteps(final OperationContext context, final ModelNode operation, final ModelNode response, final boolean recordResponse) throws OperationFailedException {

        final PathAddress originalAddress = PathAddress.pathAddress(operation.get(OP_ADDR));
        final ImmutableManagementResourceRegistration originalRegistration = context.getResourceRegistration();

        HostControllerExecutionSupport hostControllerExecutionSupport =
                HostControllerExecutionSupport.Factory.create(operation, localHostControllerInfo.getLocalHostName(),
                        new LazyDomainModelProvider(context), ignoredDomainResourceRegistry);
        ModelNode domainOp = hostControllerExecutionSupport.getDomainOperation();
        if (domainOp != null) {
            // Only require an existing registration if the domain op is not ignored
            if (originalRegistration == null) {
                throw new OperationFailedException(new ModelNode(ControllerMessages.MESSAGES.noSuchResourceType(originalAddress)));
            }
            addBasicStep(context, domainOp);
        }

        ServerOperationResolver resolver = new ServerOperationResolver(localHostControllerInfo.getLocalHostName(), serverProxies);
        ServerOperationsResolverHandler sorh = new ServerOperationsResolverHandler(
                resolver, hostControllerExecutionSupport, originalAddress, originalRegistration, response);
        context.addStep(sorh, OperationContext.Stage.DOMAIN);
    }

    /**
     * Directly handles the op in the standard way the default prepare step handler would
     * @param context the operation execution context
     * @param operation the operation
     * @throws OperationFailedException if no handler is registered for the operation
     */
    private void addBasicStep(OperationContext context, ModelNode operation) throws OperationFailedException {
        final String operationName = operation.require(OP).asString();

        final OperationStepHandler stepHandler = context.getResourceRegistration().getOperationHandler(PathAddress.EMPTY_ADDRESS, operationName);
        if(stepHandler != null) {
            context.addStep(operation, stepHandler, OperationContext.Stage.MODEL);
        } else {
            throw new OperationFailedException(new ModelNode(ControllerMessages.MESSAGES.noHandlerForOperation(operationName, PathAddress.pathAddress(operation.get(OP_ADDR)))));
        }
    }

    boolean isResourceExcluded(final PathAddress address) {
        return ignoredDomainResourceRegistry.isResourceExcluded(address);
    }

    /** Lazily provides a copy of the domain model */
    private static class LazyDomainModelProvider implements HostControllerExecutionSupport.DomainModelProvider {
        private final OperationContext context;
        private ModelNode domainModel;

        private LazyDomainModelProvider(OperationContext context) {
            this.context = context;
        }

        public ModelNode getDomainModel() {
            if (domainModel == null) {
                domainModel = Resource.Tools.readModel(context.readResourceFromRoot(PathAddress.EMPTY_ADDRESS, true));
            }
            return domainModel;
        }
    }
}
