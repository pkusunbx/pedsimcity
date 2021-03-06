package sim.app.geo.pedsimcity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sim.app.geo.urbanmason.NodeGraph;
import sim.util.geo.GeomPlanarGraphDirectedEdge;

public class CombinedNavigation{

	NodeGraph originNode, destinationNode;

	AgentProperties ap = new AgentProperties();
	ArrayList<GeomPlanarGraphDirectedEdge> completePath =  new ArrayList<GeomPlanarGraphDirectedEdge>();
	ArrayList<NodeGraph> sequenceNodes = new ArrayList<NodeGraph>();

	public ArrayList<GeomPlanarGraphDirectedEdge> path(NodeGraph originNode, NodeGraph destinationNode, AgentProperties agp) {
		this.ap = agp;
		this.originNode = originNode;
		this.destinationNode = destinationNode;
		RoutePlanner planner = new RoutePlanner();

		//regional routing necessary Yes/No based on threshold? -- does not change the general agent's property
		if (NodeGraph.nodesDistance(originNode,  destinationNode) < UserParameters.regionBasedNavigationThreshold
				|| originNode.region == destinationNode.region) ap.regionBasedNavigation = false;

		if (this.ap.regionBasedNavigation) {
			RegionBasedNavigation regionsPath = new RegionBasedNavigation();
			sequenceNodes = regionsPath.sequenceRegions(originNode, destinationNode, ap);
		}

		// through barrier (sub-goals), already computed above
		if (ap.barrierBasedNavigation && !ap.regionBasedNavigation) {
			BarrierBasedNavigation barriersPath = new BarrierBasedNavigation();
			sequenceNodes = barriersPath.sequenceBarriers(originNode, destinationNode, ap);
			System.out.println("using barriers sub-goals");}

		// through local landmarks or important nodes (sub-goals)
		else if (ap.landmarkBasedNavigation || ap.nodeBasedNavigation ) {
			// when ap.nodeBasedNavigation ap.landmarkBasedNavigation is false;
			if (ap.regionBasedNavigation && sequenceNodes.size() > 0) intraRegionMarks();
			else sequenceNodes = LandmarkNavigation.onRouteMarks(originNode, destinationNode, ap);
		}
		// pure global landmark navigation (no heuristic, no sub-goals, it allows)
		else if  (ap.usingGlobalLandmarks && !ap.landmarkBasedNavigation && ap.localHeuristic == "" && !ap.regionBasedNavigation) {
			System.out.println("returning pure global");
			return planner.globalLandmarksPath(originNode, destinationNode, ap);
		}
		Set<NodeGraph> set = new HashSet<NodeGraph>(sequenceNodes);
		if(set.size() < sequenceNodes.size()) System.out.println("DUPLICATES---------------------");

		List<Integer> opo = new ArrayList<Integer>();
		for (NodeGraph n : sequenceNodes) opo.add(n.getID());
		System.out.println(Arrays.asList(opo));

		if (sequenceNodes.size() == 0) {
			System.out.println("Path only heuristic "+ap.localHeuristic);
			if (ap.localHeuristic.equals("roadDistance")) return planner.roadDistance(originNode, destinationNode, ap);
			else if (ap.localHeuristic.equals("angularChange") || ap.localHeuristic.equals("turns"))
				return planner.angularChangeBased(originNode, destinationNode, ap);
			else if (ap.usingGlobalLandmarks && ap.localHeuristic == "") return planner.globalLandmarksPath(originNode, destinationNode, ap);
		}
		if (ap.localHeuristic.equals("roadDistance")) {
			System.out.println("Path: "+ap.localHeuristic +" with regions: "+ ap.regionBasedNavigation + ", local "+ ap.landmarkBasedNavigation +
					" or nodeBased " +ap.nodeBasedNavigation);
			return planner.roadDistanceSequence(sequenceNodes, ap);
		}
		else if (ap.localHeuristic.equals("angularChange") || ap.localHeuristic.equals("turns")) {
			System.out.println("Path: "+ap.localHeuristic+" with regions: "+ ap.regionBasedNavigation + ", local "+ ap.landmarkBasedNavigation +
					" or nodeBased " +ap.nodeBasedNavigation);
			return planner.angularChangeBasedSequence(sequenceNodes, ap);
		}
		else if (ap.usingGlobalLandmarks && ap.localHeuristic == "") {
			System.out.println("only GL --- " + sequenceNodes.size());
			return planner.globalLandmarksPathSequence(sequenceNodes, ap);
		}
		else {
			System.out.println("nothing was assigned here -------------------------------");
			return null;
		}
	}


	public void intraRegionMarks() {

		NodeGraph currentLocation = originNode;
		ArrayList<NodeGraph> newSequence = new ArrayList<NodeGraph>();

		for (NodeGraph exitGateway : this.sequenceNodes) {
			if (exitGateway == originNode || currentLocation == destinationNode) continue;
			newSequence.add(currentLocation);
			if (currentLocation.region != exitGateway.region) {
				currentLocation = exitGateway;
				continue;
			}
			ArrayList<NodeGraph> onRouteMarks = new ArrayList<NodeGraph>();
			// works also for nodeBasedNavigation only:
			onRouteMarks = onRouteMarksRegion(currentLocation, exitGateway, originNode, destinationNode, newSequence, ap);

			if (onRouteMarks.size() == 0 && ap.agentKnowledge <= UserParameters.noobAgentThreshold) {
				System.out.println("using barriers instead");
				BarrierBasedNavigation barrierBasedPath = new BarrierBasedNavigation();
				onRouteMarks = barrierBasedPath.sequenceBarriers(currentLocation, exitGateway, ap);
			}

			newSequence.addAll(onRouteMarks);
			//			List<Integer> opo = new ArrayList<Integer>();
			//			for (NodeGraph n : onRouteMarks) opo.add(n.getID());
			//			System.out.println("control onroutemarks "+Arrays.asList(opo));
			currentLocation = exitGateway;
		}
		newSequence.add(destinationNode);
		sequenceNodes = newSequence;
	}

	/**
	 * It generates a sequence of intermediate between two nodes (origin, destination) on the basis of local landmarkness (identification of
	 * "on-route marks"). The nodes are considered are salient junctions within a certain space, namely junctions likely to be cognitively
	 * represented. These are identified on the basis of betweenness centrality values.
	 *
	 * @param originNode the origin node;
	 * @param destinationNode the destination node;
	 * @param regionBasedNavigation  if true, when using regions, it examines only salient nodes within the region;
	 * @param typeLandmarkness it indicates whether the wayfinding complexity towards the destination should be computed by using
	 * 		local or global landmarks;
	 */
	public static ArrayList<NodeGraph> onRouteMarksRegion(NodeGraph currentLocation, NodeGraph exitGateway,
			NodeGraph originNode, NodeGraph destinationNode, ArrayList<NodeGraph> sequenceSoFar, AgentProperties ap) {

		double percentile = UserParameters.salientNodesPercentile;
		ArrayList<NodeGraph> sequence = new ArrayList<NodeGraph>();
		Region region = PedSimCity.regionsMap.get(currentLocation.region);
		Map<NodeGraph, Double> knownJunctions =  region.primalGraph.salientNodesNetwork(percentile);

		// If no salient junctions are found, the tolerance increases till the 0.50 percentile;
		// still no salient junctions are found, the agent continues without landmarks
		while (knownJunctions == null) {
			percentile -= 0.05;
			if (percentile < 0.50) return sequence;
			knownJunctions = region.primalGraph.salientNodesNetwork(percentile);
		}
		// compute wayfinding complexity and the resulting easinesss
		double wayfindingEasiness = wayfindingEasinessRegion(currentLocation, exitGateway, originNode, destinationNode, ap.typeLandmarks);
		double searchDistance = NodeGraph.nodesDistance(currentLocation, exitGateway) * (wayfindingEasiness);

		// while the wayfindingEasiness is lower than the threshold the agent looks for intermediate-points.
		while (wayfindingEasiness < UserParameters.wayfindingEasinessThreshold) {
			NodeGraph bestNode = null;
			double attractivness = 0.0;

			ArrayList<NodeGraph> junctions = new ArrayList<NodeGraph>(knownJunctions.keySet());
			double maxCentrality = Collections.max(knownJunctions.values());
			double minCentrality = Collections.min(knownJunctions.values());
			for (NodeGraph tmpNode : junctions) {
				// bad candidates (candidate is origin or already visited, etc)
				if (sequence.contains(tmpNode) || tmpNode == currentLocation || tmpNode.getEdgeWith(currentLocation) != null) continue;
				if (NodeGraph.nodesDistance(currentLocation, tmpNode) > searchDistance) continue; //only nodes in range
				if (NodeGraph.nodesDistance(tmpNode, exitGateway) > NodeGraph.nodesDistance(currentLocation, exitGateway)) continue;
				if (sequenceSoFar.contains(tmpNode)) continue;

				double score = 0.0;
				if (ap.landmarkBasedNavigation) score = LandmarkNavigation.localLandmarkness(tmpNode);
				else score = (tmpNode.centrality-minCentrality)/(maxCentrality-minCentrality);
				double currentDistance = NodeGraph.nodesDistance(currentLocation, exitGateway);
				double gain = (currentDistance - NodeGraph.nodesDistance(tmpNode, exitGateway))/currentDistance;

				double tmp = score*0.50 + gain*0.50;
				if (tmp > attractivness) {
					attractivness = tmp;
					bestNode = tmpNode;
				}
			}

			if (bestNode == null || bestNode == exitGateway || bestNode == destinationNode) break;
			sequence.add(bestNode);

			percentile = UserParameters.salientNodesPercentile;
			knownJunctions = region.primalGraph.salientNodesNetwork(percentile);
			while (knownJunctions == null) {
				percentile -= 0.05;
				if (percentile < 0.50) return sequence;
				knownJunctions = region.primalGraph.salientNodesNetwork(percentile);
			}
			wayfindingEasiness = wayfindingEasinessRegion(bestNode, exitGateway, originNode, destinationNode, ap.typeLandmarks);
			searchDistance = NodeGraph.nodesDistance(bestNode, exitGateway) * wayfindingEasiness;
			currentLocation = bestNode;
			bestNode = null;
		}
		return sequence;
	}
	/**
	 * It computes the wayfinding easiness within a region on the basis of:
	 * - the ratio between the distance that would be walked within the region considered and the distance between the origin and the destination;
	 * - the legibility complexity, based on the presence of landmarks within the region;
	 *
	 * @param originNode the origin node of the whole trip;
	 * @param destinationNode the origin node of the whole trip;
	 * @param tmpOrigin the intermediate origin node, within the region;
	 * @param tmpDestination the intermediate destination node, within the region;
	 */
	public static double wayfindingEasinessRegion(NodeGraph currentLocation, NodeGraph exitGateway,NodeGraph originNode, NodeGraph destinationNode,
			String typeLandmarkness) {

		double intraRegionDistance = NodeGraph.nodesDistance(currentLocation, exitGateway);
		double distance = NodeGraph.nodesDistance(originNode, destinationNode);
		double distanceComplexity = intraRegionDistance/distance;
		if (distanceComplexity < 0.20) return 1.0;

		double buildingsComplexity = PedSimCity.regionsMap.get(currentLocation.region).computeComplexity(typeLandmarkness);
		double wayfindingComplexity = (distanceComplexity + buildingsComplexity)/2.0;
		double easiness = 1.0 - wayfindingComplexity;
		//		System.out.println("buildingsComplexity "+ buildingsComplexity + " distance Complexity " + distanceComplexity);
		return easiness;
	}
}




