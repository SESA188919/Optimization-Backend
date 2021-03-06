/**
 * Copyright (C) 2012 Schneider-Electric
 *
 * This file is part of "Mind Compiler" is free software: you can redistribute 
 * it and/or modify it under the terms of the GNU Lesser General Public License 
 * as published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT 
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Contact: mind@ow2.org
 *
 * Authors: Stephane Seyvoz, Assystem (for Schneider-Electric)
 * Contributors: 
 */
package org.ow2.mind.adl;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.objectweb.fractal.adl.ADLException;
import org.objectweb.fractal.adl.Definition;
import org.objectweb.fractal.adl.Loader;
import org.objectweb.fractal.adl.Node;
import org.objectweb.fractal.adl.interfaces.Interface;
import org.objectweb.fractal.adl.interfaces.InterfaceContainer;
import org.objectweb.fractal.adl.types.TypeInterface;
import org.ow2.mind.adl.ast.ASTHelper;
import org.ow2.mind.adl.ast.AttributeContainer;
import org.ow2.mind.adl.ast.Binding;
import org.ow2.mind.adl.ast.BindingContainer;
import org.ow2.mind.adl.ast.Component;
import org.ow2.mind.adl.ast.ComponentContainer;
import org.ow2.mind.adl.ast.OptimASTHelper;
import org.ow2.mind.adl.ast.Source;
import org.ow2.mind.adl.graph.BindingInstantiator.BindingDescriptor;
import org.ow2.mind.adl.graph.ComponentGraph;
import org.ow2.mind.adl.membrane.ast.Controller;
import org.ow2.mind.adl.membrane.ast.ControllerContainer;
import org.ow2.mind.compilation.CompilationCommand;
import org.ow2.mind.error.ErrorManager;
import org.ow2.mind.inject.InjectDelegate;

import com.google.inject.Inject;

/**
 * @author Stephane Seyvoz, Assystem
 *
 */
public class BasicInternalDataOptimizer implements InternalDataOptimizer {

	// Annotation used to specify that the field is used to store the reference to the next object of the delegation chain.
	@InjectDelegate
	protected GraphCompiler 	clientCompilerItf;

	@Inject
	protected Loader 			loaderItf;
	
	@Inject
	protected ErrorManager		errorManagerItf;

	// ---------------------------------------------------------------------------
	// Implementation of the Visitor interface
	// ---------------------------------------------------------------------------

	/* (non-Javadoc)
	 * @see org.ow2.mind.Visitor#visit(java.lang.Object, java.util.Map)
	 */
	public Collection<CompilationCommand> visit(ComponentGraph graph,
			Map<Object, Object> context) throws ADLException {


		visitGraphForOptimizationChecks(graph, context);

		// After decorating the AST we give the hand back to the standard BasicGraphCompiler
		return clientCompilerItf.visit(graph, context);
	}

	/*
	 * Recursive check on the whole tree for possible optimizations detection
	 */
	protected boolean visitGraphForOptimizationChecks(ComponentGraph graph, Map<Object, Object> context) throws ADLException {

		boolean result = false;
		boolean subCompOptimized = false;
		int numberOfOptimizedSubComps = 0;

		Boolean externalTypeOptimizationAllowed = false;
		Boolean internalTypeOptimizationAllowed = false;

		Definition compDef = graph.getDefinition();

		// Check if the current component is the top-level one (the whole application) (maybe there is a cleaner way ?)
		// And it doesn't provide/require interfaces
		if ((graph.getParents().length == 0)
				&& ((!(compDef instanceof InterfaceContainer))
						|| ((compDef instanceof InterfaceContainer)
								&& ((((InterfaceContainer) compDef).getInterfaces() == null)
										|| (((InterfaceContainer) compDef).getInterfaces() != null) && (((InterfaceContainer) compDef).getInterfaces().length == 0))))){
			externalTypeOptimizationAllowed = true;
			internalTypeOptimizationAllowed = true;
		} else {	
			// First check for type data
			// Details:
			// - We do not optimize unbound client interfaces
			// - StaticBindings allows deleting client and server data
			// - Static only allows deleting client data (so to allow external type optimization in this case, there has to be no server data at all)
			externalTypeOptimizationAllowed = OptimASTHelper.isSingleton(compDef)
					&& (parentIsTaggedStaticBindings(graph)
							|| (!parentIsTaggedStaticBindings(graph) && !existsNonStaticBindingFromComponent(graph) && !existsServerInterface(graph)))
					&& (!existsOptionalUnboundClientInterface(graph))
					&& (!existsDestinationControllerInterfaceAndDecorateIt(graph, context))
					&& (!hostsController_ExceptDelegator(compDef));

			// Check for inner_type data
			internalTypeOptimizationAllowed = OptimASTHelper.isSingleton(compDef)
					&& (OptimASTHelper.isPrimitive(compDef) /* no inner_type data */
							||
							(OptimASTHelper.isComposite(compDef)
									/* External client - dual internal server of the composite membrane */
									&& (!existsOptionalUnboundClientInterface(graph))
									/* External server - dual internal client of the composite membrane */
									&& (isTaggedStaticBindings(graph))
									)
							);
			/*
			 * We needed to handle the case where the user wants to optimize the current composite A with StaticBindings, but doesn't want
			 * @StaticBindings on its internal composite B : as we have to keep B's internal interfaces data (even if its external interfaces are
			 * optimized, we don't want to remove the whole internal data).
			 * 
			 * Note: according to the StaticBindingsAnnotationProcessor, primitive sub-components are never tagged nor decorated with StaticBindings
			 * As only composites can be tagged StaticBindings, we don't even test if they are composite.
			 */
		}

		if (externalTypeOptimizationAllowed)
			setAllowExternalTypeDataRemovalDecoration(graph);

		if (internalTypeOptimizationAllowed)
			setAllowInternalTypeDataRemovalDecoration(graph);

		// Then check for the whole instance (containing the type data and more)
		// TODO : Check if the ControllerContainer test suffices
		if (externalTypeOptimizationAllowed && internalTypeOptimizationAllowed
				&& (!(OptimASTHelper.isComposite(compDef))) /* Composite */
				&& (!(compDef instanceof AttributeContainer && (((AttributeContainer) compDef).getAttributes() != null) && (((AttributeContainer) compDef).getAttributes().length != 0))) /* Attributes */
				&& (!(compDef instanceof ControllerContainer)) /* Controllers*/
				// Since "new singleton private data" using external global variable for PRIVATE
				// (not inside _internal_data struct anymore), the following issue was made irrelevant:
				//	&& (!(compDef instanceof ImplementationContainer && ((ImplementationContainer) compDef).getData() != null)) /* Private data */
				)
		{
			setAllowInstanceDataRemovalDecoration(graph);
			result = true;
		}

		// Note when using such optimization :
		// - Parent components have references to the inner component structure
		// - DECLARE_INSTANCE (.adl.h) too
		// - INIT_INSTANCE too (.adl.h) too

		ComponentGraph[] subComponents = graph.getSubComponents();

		// visit sub-components
		for (ComponentGraph subComponentGraph : subComponents) {

			// Checking sub - ComponentGraph(s)
			subCompOptimized = visitGraphForOptimizationChecks(subComponentGraph, context);

			// We want to decorate sub - Components for selective data removal in header definition .adl.h StringTemplate
			// We need to match both to decorate the current one
			if (subCompOptimized){
				Component[] subComps = ((ComponentContainer) graph.getDefinition()).getComponents();

				int i = 0;
				while ((i < subComps.length) && !subComps[i].getName().equals(subComponentGraph.getNameInParent(graph))) i++;
				if (i < subComps.length) // means we found it
					setAllowInstanceDataRemovalDecoration(subComps[i]);

				// We need to check if all subComps have been optimized
				// If all were, then we can remove the whole "{ } components;" empty structure
				numberOfOptimizedSubComps++;
			}
		}

		// We know all sub-components were optimized, so their "internal_data" structures do not exist anymore
		// So we can remove the whole "{ } components;" empty structure
		if (OptimASTHelper.isComposite(compDef) && (subComponents.length == numberOfOptimizedSubComps)) {
			setAllowSubComponentRemovalDecoration(graph);

			// We need to check AGAIN if NOW the whole internal data are optimized (maybe only composite information remained)
			// If yes, we can remove instance-data
			// We also remove the check for private datas (ImplementationContainer etc...) because composites have no implementation and no data. 
			if (externalTypeOptimizationAllowed && internalTypeOptimizationAllowed
					&& (!(compDef instanceof AttributeContainer && (((AttributeContainer) compDef).getAttributes() != null) && (((AttributeContainer) compDef).getAttributes().length != 0))) /* Attributes */
					/*&& (!(compDef instanceof ControllerContainer))*/ /* Controllers*/ ){
				setAllowInstanceDataRemovalDecoration(graph);
				result = true;
			}
		}

		return result;
	}

	private boolean existsServerInterface(ComponentGraph instanceGraph) {
		Node node = (Node) instanceGraph.getDefinition();
		if (node instanceof InterfaceContainer){
			InterfaceContainer itfCtnr = (InterfaceContainer) node;
			for (Interface currentInterface : itfCtnr.getInterfaces()){
				// Every interface seems to be a TypeInterface anyway...
				if (currentInterface instanceof TypeInterface) {
					if (((TypeInterface) currentInterface).getRole().equals(TypeInterface.SERVER_ROLE))
						return true;
				}
			}
		}
		return false;
	}

	protected boolean existsCollectionInterface(ComponentGraph instanceGraph){
		Node node = (Node) instanceGraph.getDefinition();
		if (node instanceof InterfaceContainer){
			InterfaceContainer itfCtnr = (InterfaceContainer) node;
			for (Interface currentInterface : itfCtnr.getInterfaces()){
				// Every interface seems to be a TypeInterface anyway...
				if (currentInterface instanceof TypeInterface) {
					TypeInterface currentTypeInterface = (TypeInterface) currentInterface;
					if ((currentTypeInterface.getCardinality() != null) && currentTypeInterface.getCardinality().equals(TypeInterface.COLLECTION_CARDINALITY))
						return true;
				}
			}
		}
		return false;
	}

	protected boolean existsDestinationCollectionInterface(ComponentGraph instanceGraph){

		ComponentGraph parents[] = instanceGraph.getParents();

		// We make the hypothesis that there will be only one parent
		// No management of shared components yet
		if (parents.length == 1) {
			Definition parentDef = parents[0].getDefinition();
			if (parentDef instanceof BindingContainer){
				for (Binding parentBinding : ((BindingContainer) parentDef).getBindings()){
					// current binding source is the current component
					if (parentBinding.getFromComponent().equals(instanceGraph.getNameInParent(parents[0])))
						// Is current binding destination  an element of a collection interface ?
						if (OptimASTHelper.getToInterfaceNumber(parentBinding) != -1)
							return true;
				}
			}
		} else {
			// TODO : Log warning/error message to tell the user that a shared component is an issue for us ?
			// and check the return value
			return false;
		}

		return false;
	}

	protected boolean existsDestinationControllerInterfaceAndDecorateIt(ComponentGraph instanceGraph, Map<Object, Object> context) {
		boolean result = false;

		ComponentGraph parents[] = instanceGraph.getParents();

		Map<String, BindingDescriptor> descs;

		// We make the hypothesis that there will be only one parent
		// No management of shared components yet
		if (parents.length == 1) {
			ComponentGraph[] thisGraphAndSiblings = parents[0].getSubComponents();
			for (ComponentGraph currentSibling : thisGraphAndSiblings){
				// We do not check to remove current component because self-bindings are allowed, we want to care about them too
				descs = (Map<String, BindingDescriptor>) currentSibling.getDecoration("binding-descriptors");
				if (!descs.isEmpty()){
					Collection<String> keys = descs.keySet();
					Iterator<String> keysIterator = keys.iterator();

					while(keysIterator.hasNext()) {
						String currentKey = keysIterator.next();
						BindingDescriptor bindingDesc = (BindingDescriptor) descs.get(currentKey);
						Binding parentBinding = bindingDesc.binding;

						// check if current binding source is the current component
						// also check if a binding isn't null : happens when an element of a collection interface isn't bound
						if ((parentBinding != null) && (parentBinding.getFromComponent().equals(instanceGraph.getNameInParent(parents[0])))) {
							// Is current binding destination a controller ?
							String srcCompName = parentBinding.getFromComponent();
							String srcInterfaceName = parentBinding.getFromInterface();
							String destCompName = parentBinding.getToComponent();
							String destInterfaceName = parentBinding.getToInterface();


							try {
								// We need to resolve the destination interface to check for its decorations
								Node parentDef = parents[0].getDefinition();
								Definition destCompDef;

								if (destCompName.equals("this"))
									destCompDef = instanceGraph.getDefinition(); // TODO: check if we should compute InternalInterface-based instead later ?
								else {
									Component destComp = ASTHelper.getComponent(parentDef, destCompName);
									destCompDef = ASTHelper.getResolvedComponentDefinition(destComp, loaderItf, context);
								}

								Interface destItf = ASTHelper.getInterface(destCompDef, destInterfaceName);

								Object controllerDecoration = destItf.astGetDecoration("controller-interface");
								if ((controllerDecoration != null) && (controllerDecoration instanceof Boolean)) {
									//System.out.println("DIRTY DEBUG: Destination interface " + destInterfaceName + " from component " + destCompName + " is a controller !");
									result = true;

									Definition srcCompDef;
									if (srcCompName.equals("this"))
										srcCompDef = instanceGraph.getDefinition(); // TODO: check if we should compute InternalInterface-based instead later ?
									else {
										Component srcComp = ASTHelper.getComponent(parentDef, srcCompName);
										srcCompDef = ASTHelper.getResolvedComponentDefinition(srcComp, loaderItf, context);
									}

									Interface srcItf = ASTHelper.getInterface(srcCompDef, srcInterfaceName);
									// Here we put the DESTINATION name of the binding as we use REVERSE resolution from the binding
									srcItf.astSetDecoration("binding-destination-is-controller", true);
								}
							} catch (ADLException e) {
								// TODO: handle ASTHelper.getResolvedComponentDefinition possible error
								e.printStackTrace();
							}
						}
					}
				}
			}
		} else {
			// TODO : Log warning/error message to tell the user that a shared component is an issue for us ?
			// and check the return value
			return result;
		}

		return result;
	}

	protected boolean hostsController_ExceptDelegator(Definition def) {
		if (! (def instanceof ControllerContainer))
			return false;

		Controller[] ctrlArray = ((ControllerContainer) def).getControllers();

		if (ctrlArray.length < 1)
			return false;

		boolean foundDelegator = false;
		for (Controller currCtrlr : ctrlArray) {
			Source[] currCtrlrSources = currCtrlr.getSources();
			for (Source currSource : currCtrlrSources) {
				if (currSource.getPath().equals("InterfaceDelegator")) 
					foundDelegator = true;
			}
		}

		if (foundDelegator)
			if (ctrlArray.length > 1)
				return true;
			else
				return false;
		else return true;
	}

	protected boolean parentIsTaggedStaticBindings(ComponentGraph graph){
		Boolean parentHasStaticBindings = (Boolean) graph.getDefinition().astGetDecoration("parent-has-static-bindings");
		if ((parentHasStaticBindings != null) && parentHasStaticBindings.equals(Boolean.TRUE))
			return true;
		return false;
	}

	protected boolean isTaggedStaticBindings(ComponentGraph graph){
		Boolean hasStaticBindings = (Boolean) graph.getDefinition().astGetDecoration("has-static-bindings");
		if ((hasStaticBindings != null) && hasStaticBindings.equals(Boolean.TRUE))
			return true;
		return false;
	}

	/**
	 * 
	 * @param instanceGraph
	 * @throws ADLException 
	 */
	protected boolean existsNonStaticBindingFromComponent(ComponentGraph instanceGraph) throws ADLException {
		Node node = (Node) instanceGraph.getDefinition();
		if (node instanceof InterfaceContainer){
			InterfaceContainer itfCtnr = (InterfaceContainer) node;
			for (Interface currentInterface : itfCtnr.getInterfaces()){
				// Every interface seems to be a TypeInterface anyway...
				if (currentInterface instanceof TypeInterface) {
					TypeInterface currentTypeInterface = (TypeInterface) currentInterface;
					if (currentTypeInterface.getRole().equals(TypeInterface.CLIENT_ROLE)){
						if (!isStatic(currentTypeInterface, instanceGraph))
							return true;
					}
				}
			}
		}
		return false;
	}

	// Interfaces signatures
	protected void setAllowExternalTypeDataRemovalDecoration(ComponentGraph instanceGraph){
		instanceGraph.setDecoration("allow-external-type-data-removal", "true");
	}

	// Interfaces signatures
	protected void setAllowInternalTypeDataRemovalDecoration(ComponentGraph instanceGraph){
		instanceGraph.setDecoration("allow-internal-type-data-removal", "true");
	}

	// this
	protected void setAllowInstanceDataRemovalDecoration(ComponentGraph instanceGraph){
		instanceGraph.setDecoration("allow-instance-data-removal", "true");
	}

	// this
	protected void setAllowInstanceDataRemovalDecoration(Node node){
		node.astSetDecoration("allow-instance-data-removal", "true");
	}

	// Sub-components
	protected void setAllowSubComponentRemovalDecoration(ComponentGraph graph){
		graph.setDecoration("allow-subcomps-data-removal", "true");
	}

	protected boolean isBound(TypeInterface typeInterface, ComponentGraph instanceGraph) {
		Map<String, BindingDescriptor> descs = (Map<String, BindingDescriptor>) instanceGraph.getDecoration("binding-descriptors");

		// binding-descriptors are systematically created even if a client interface is not bound !
		// we've got to check if the DESTINATION is null !

		// Get the binding
		if (descs.get(typeInterface.getName()).binding != null) {
			//System.out.println("Component " + instanceGraph.getNameInParent(instanceGraph.getParents()[0]) + " interface \"" + typeInterface.getName() + "\" is bound.");
			return true;
		} else {
			//System.out.println("Component " + instanceGraph.getNameInParent(instanceGraph.getParents()[0]) + " interface \"" + typeInterface.getName() + "\" is not bound.");
			return false;
		}
	}
	
	protected boolean isStatic(TypeInterface typeInterface, ComponentGraph instanceGraph) {
		Map<String, BindingDescriptor> descs = (Map<String, BindingDescriptor>) instanceGraph.getDecoration("binding-descriptors");

		// binding-descriptors are systematically created even if a client interface is not bound !
		// we've got to check if the DESTINATION is null !
		
		BindingDescriptor desc = descs.get(typeInterface.getName());
		
		if (desc == null)
			return false;
		
		Binding binding = desc.binding;
		
		if (binding == null)
			return false;
		
		Boolean isStatic = (Boolean) binding.astGetDecoration("is-static");
		
		// Get the binding
		if ((isStatic != null) && isStatic.equals(Boolean.TRUE)) {
			//System.out.println("Component " + instanceGraph.getNameInParent(instanceGraph.getParents()[0]) + " interface \"" + typeInterface.getName() + "\" is bound.");
			return true;
		} else {
			//System.out.println("Component " + instanceGraph.getNameInParent(instanceGraph.getParents()[0]) + " interface \"" + typeInterface.getName() + "\" is not bound.");
			return false;
		}
	}

	protected boolean existsOptionalUnboundClientInterface(ComponentGraph instanceGraph){
		Node node = (Node) instanceGraph.getDefinition();
		if (node instanceof InterfaceContainer){
			InterfaceContainer itfCtnr = (InterfaceContainer) node;
			for (Interface currentInterface : itfCtnr.getInterfaces()){
				// Every interface seems to be a TypeInterface anyway...
				if (currentInterface instanceof TypeInterface) {
					TypeInterface currentTypeInterface = (TypeInterface) currentInterface;
					if (currentTypeInterface.getRole().equals(TypeInterface.CLIENT_ROLE)){
						if ((currentTypeInterface.getContingency() != null) && currentTypeInterface.getContingency().equals(TypeInterface.OPTIONAL_CONTINGENCY)){
							if (!isBound(currentTypeInterface, instanceGraph))
								return true;
						}
					}
				}
			}
		}
		return false;
	}

}
