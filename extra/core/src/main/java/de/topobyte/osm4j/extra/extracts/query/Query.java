// Copyright 2015 Sebastian Kuerten
//
// This file is part of osm4j.
//
// osm4j is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// osm4j is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with osm4j. If not, see <http://www.gnu.org/licenses/>.

package de.topobyte.osm4j.extra.extracts.query;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slimjars.dist.gnu.trove.set.TLongSet;

import de.topobyte.jts.utils.predicate.PredicateEvaluator;
import de.topobyte.osm4j.core.access.OsmIterator;
import de.topobyte.osm4j.core.access.OsmIteratorInput;
import de.topobyte.osm4j.core.access.OsmStreamOutput;
import de.topobyte.osm4j.core.dataset.InMemoryListDataSet;
import de.topobyte.osm4j.core.model.iface.OsmNode;
import de.topobyte.osm4j.core.model.iface.OsmWay;
import de.topobyte.osm4j.extra.QueryUtil;
import de.topobyte.osm4j.extra.datatree.DataTree;
import de.topobyte.osm4j.extra.datatree.DataTreeFiles;
import de.topobyte.osm4j.extra.datatree.DataTreeOpener;
import de.topobyte.osm4j.extra.datatree.Node;
import de.topobyte.osm4j.extra.extracts.BatchFileNames;
import de.topobyte.osm4j.extra.extracts.ExtractionPaths;
import de.topobyte.osm4j.extra.extracts.TreeFileNames;
import de.topobyte.osm4j.extra.idbboxlist.IdBboxEntry;
import de.topobyte.osm4j.extra.idbboxlist.IdBboxUtil;
import de.topobyte.osm4j.utils.FileFormat;
import de.topobyte.osm4j.utils.OsmFileInput;
import de.topobyte.osm4j.utils.OsmOutputConfig;
import de.topobyte.osm4j.utils.merge.sorted.SortedMerge;

public class Query extends AbstractQuery
{

	final static Logger logger = LoggerFactory.getLogger(Query.class);

	private Envelope queryEnvelope;
	private PredicateEvaluator test;

	private Path pathOutput;
	private Path pathTmp;
	private ExtractionPaths paths;

	private TreeFileNames treeNames;
	private BatchFileNames relationNames;

	private boolean keepTmp;

	private boolean fastRelationTests;

	private RelationFilter relationFilter;

	/**
	 * Create a query to extract data contained in an area from an extraction
	 * database.
	 * 
	 * @param queryEnvelope
	 *            the bounding envelope of the region to extract.
	 * @param test
	 *            a PredicateEvaluator used for determining inclusion in the
	 *            extract.
	 * @param pathOutput
	 *            a path to a file to store output data in.
	 * @param pathTmp
	 *            a directory to store intermediate, temporary files (pass null
	 *            to use the system's default temporary storage, i.e.
	 *            {@link Files#createTempDirectory(String, FileAttribute...)}
	 *            will be used.)
	 * @param paths
	 *            an ExtractionPaths object configured for an extraction
	 *            database.
	 * @param treeNames
	 *            the names of the files in the data tree.
	 * @param relationNames
	 *            the names of the files in the relation batches.
	 * @param inputFormat
	 *            the {@link FileFormat} of the database extract files.
	 * @param outputConfigIntermediate
	 *            configuration for intermediate file storage
	 * @param outputConfig
	 *            configuration for the final output file.
	 * @param keepTmp
	 *            whether to keep temporary files after the extraction is done.
	 * @param fastRelationTests
	 *            whether to include relations based on their bounding box (and
	 *            not by evaluating their exact geometry).
	 * @param relationFilter
	 *            a filter to select a subset of relations with. Pass null to
	 *            select all relations. If only a subset of relations is
	 *            selected, all transitively referenced relations will be
	 *            included as well.
	 */
	public Query(Envelope queryEnvelope, PredicateEvaluator test,
			Path pathOutput, Path pathTmp, ExtractionPaths paths,
			TreeFileNames treeNames, BatchFileNames relationNames,
			FileFormat inputFormat, OsmOutputConfig outputConfigIntermediate,
			OsmOutputConfig outputConfig, boolean keepTmp,
			boolean fastRelationTests, RelationFilter relationFilter)
	{
		super(inputFormat, outputConfigIntermediate, outputConfig);

		this.queryEnvelope = queryEnvelope;
		this.test = test;
		this.pathOutput = pathOutput;
		this.pathTmp = pathTmp;
		this.paths = paths;
		this.treeNames = treeNames;
		this.relationNames = relationNames;
		this.keepTmp = keepTmp;
		this.fastRelationTests = fastRelationTests;
		this.relationFilter = relationFilter;
	}

	private Path pathTmpTreeNodes;
	private Path pathTmpTreeWays;
	private Path pathTmpTreeSimpleRelations;
	private Path pathTmpTreeComplexRelations;
	private Path pathTmpTreeAdditionalNodes;
	private Path pathTmpTreeAdditionalWays;
	private Path pathTmpSimpleNodes;
	private Path pathTmpSimpleWays;
	private Path pathTmpSimpleRelations;
	private Path pathTmpComplexNodes;
	private Path pathTmpComplexWays;
	private Path pathTmpComplexRelations;

	private GeometryFactory factory = new GeometryFactory();

	private DataTree tree;
	private DataTreeFiles filesTreeNodes;
	private DataTreeFiles filesTreeWays;
	private DataTreeFiles filesTreeSimpleRelations;
	private DataTreeFiles filesTreeComplexRelations;

	// Lists of files that need to be merged in the end
	private List<OsmFileInput> filesNodes = new ArrayList<>();
	private List<OsmFileInput> filesWays = new ArrayList<>();
	private List<OsmFileInput> filesSimpleRelations = new ArrayList<>();
	private List<OsmFileInput> filesComplexRelations = new ArrayList<>();

	private int nNodes = 0;
	private int nWays = 0;
	private int nSimpleRelations = 0;
	private int nComplexRelations = 0;

	private int tmpIndexTree = 0;

	public void execute() throws IOException
	{
		createTemporaryDirectory();

		// Query setup

		openTree();

		// Query data tree

		queryTreeData();

		// Query relations

		queryRelations();

		// Merge intermediate files

		mergeFiles();

		// Delete intermediate files

		if (!keepTmp) {
			FileUtils.deleteDirectory(pathTmp.toFile());
		}
	}

	private void createTemporaryDirectory() throws IOException
	{
		// Make sure a temporary directory is available

		if (pathTmp == null) {
			pathTmp = Files.createTempDirectory("extract");
		}
		logger.info("Temporary directory: " + pathTmp);
		Files.createDirectories(pathTmp);
		if (!Files.isDirectory(pathTmp)) {
			String error = "Unable to create temporary directory for intermediate files";
			logger.error(error);
			throw new IOException(error);
		}
		if (pathTmp.toFile().listFiles().length != 0) {
			String error = "Temporary directory for intermediate files is not empty";
			logger.error(error);
			throw new IOException(error);
		}
		logger.info("Storing intermediate files here: " + pathTmp);

		// Create sub-directories for intermediate files

		Path pathTmpTree = pathTmp.resolve("tree");
		Path pathTmpSimple = pathTmp.resolve("simple-relations");
		Path pathTmpComplex = pathTmp.resolve("complex-relations");

		pathTmpTreeNodes = pathTmpTree.resolve("nodes");
		pathTmpTreeWays = pathTmpTree.resolve("ways");
		pathTmpTreeSimpleRelations = pathTmpTree.resolve("relations.simple");
		pathTmpTreeComplexRelations = pathTmpTree.resolve("relations.complex");
		pathTmpTreeAdditionalNodes = pathTmpTree.resolve("nodes-extra");
		pathTmpTreeAdditionalWays = pathTmpTree.resolve("ways-extra");

		pathTmpSimpleNodes = pathTmpSimple.resolve("nodes");
		pathTmpSimpleWays = pathTmpSimple.resolve("ways");
		pathTmpSimpleRelations = pathTmpSimple.resolve("relations");
		pathTmpComplexNodes = pathTmpComplex.resolve("nodes");
		pathTmpComplexWays = pathTmpComplex.resolve("ways");
		pathTmpComplexRelations = pathTmpComplex.resolve("relations");

		Files.createDirectory(pathTmpTree);
		Files.createDirectory(pathTmpSimple);
		Files.createDirectory(pathTmpComplex);

		Files.createDirectory(pathTmpTreeNodes);
		Files.createDirectory(pathTmpTreeWays);
		Files.createDirectory(pathTmpTreeSimpleRelations);
		Files.createDirectory(pathTmpTreeComplexRelations);
		Files.createDirectory(pathTmpTreeAdditionalNodes);
		Files.createDirectory(pathTmpTreeAdditionalWays);

		Files.createDirectory(pathTmpSimpleNodes);
		Files.createDirectory(pathTmpSimpleWays);
		Files.createDirectory(pathTmpSimpleRelations);
		Files.createDirectory(pathTmpComplexNodes);
		Files.createDirectory(pathTmpComplexWays);
		Files.createDirectory(pathTmpComplexRelations);
	}

	private void openTree() throws IOException
	{
		Path pathTree = paths.getTree();
		tree = DataTreeOpener.open(pathTree);

		filesTreeNodes = new DataTreeFiles(pathTree, treeNames.getNodes());
		filesTreeWays = new DataTreeFiles(pathTree, treeNames.getWays());
		filesTreeSimpleRelations = new DataTreeFiles(pathTree,
				treeNames.getSimpleRelations());
		filesTreeComplexRelations = new DataTreeFiles(pathTree,
				treeNames.getComplexRelations());
	}

	private void queryTreeData() throws IOException
	{
		Geometry box = factory.toGeometry(queryEnvelope);
		List<Node> leafs = tree.query(box);

		for (Node leaf : leafs) {
			String leafName = Long.toHexString(leaf.getPath());

			if (test.contains(leaf.getEnvelope())) {
				logger.info("Leaf is completely contained: " + leafName);
				addCompletelyContainedLeaf(leaf);
				continue;
			}

			logger.info("Loading data from leaf: " + leafName);
			addIntersectingLeaf(leaf);
		}

		logger.info(String.format("Total number of nodes: %d", nNodes));
		logger.info(String.format("Total number of ways: %d", nWays));
		logger.info(String.format("Total number of simple relations: %d",
				nSimpleRelations));
		logger.info(String.format("Total number of complex relations: %d",
				nComplexRelations));
	}

	private void queryRelations() throws IOException
	{
		List<IdBboxEntry> entriesSimple = IdBboxUtil
				.read(paths.getSimpleRelationsBboxes());
		List<IdBboxEntry> entriesComplex = IdBboxUtil
				.read(paths.getComplexRelationsBboxes());

		queryRelationBatches(entriesSimple, true, "Simple",
				paths.getSimpleRelations(), filesSimpleRelations,
				pathTmpSimpleNodes, pathTmpSimpleWays, pathTmpSimpleRelations);
		queryRelationBatches(entriesComplex, false, "Complex",
				paths.getComplexRelations(), filesComplexRelations,
				pathTmpComplexNodes, pathTmpComplexWays,
				pathTmpComplexRelations);
	}

	private void queryRelationBatches(List<IdBboxEntry> entries, boolean simple,
			String type, Path pathRelationBatches,
			List<OsmFileInput> filesRelations, Path pathTmpNodes,
			Path pathTmpWays, Path pathTmpRelations) throws IOException
	{
		int tmpIndex = 0;

		String lowerType = type.toLowerCase();
		for (IdBboxEntry entry : entries) {
			long id = entry.getId();
			if (test.contains(entry.getEnvelope())) {
				logger.info(type + " batch completely contained: " + id);
				addCompletelyContainedBatch(pathRelationBatches, id,
						filesRelations);
			} else if (test.intersects(entry.getEnvelope())) {
				logger.info("Loading data from " + lowerType + " batch: " + id);
				tmpIndex++;
				String tmpFilenames = filename(tmpIndex);
				logger.info("Writing to files: " + tmpFilenames);

				Path pathDir = pathRelationBatches
						.resolve(Long.toString(entry.getId()));
				Path pathNodes = pathDir.resolve(relationNames.getNodes());
				Path pathWays = pathDir.resolve(relationNames.getWays());
				Path pathRelations = pathDir
						.resolve(relationNames.getRelations());

				Path pathOutNodes = pathTmpNodes.resolve(tmpFilenames);
				Path pathOutWays = pathTmpWays.resolve(tmpFilenames);
				Path pathOutRelations = pathTmpRelations.resolve(tmpFilenames);

				runRelationsQuery(simple, tmpFilenames, pathNodes, pathWays,
						pathRelations, pathOutNodes, pathOutWays,
						pathOutRelations);
			}
		}
	}

	private void mergeFiles() throws IOException
	{
		OsmStreamOutput output = createFinalOutput(pathOutput);

		List<OsmFileInput> mergeFiles = new ArrayList<>();

		mergeFiles.addAll(filesNodes);
		mergeFiles.addAll(filesWays);
		mergeFiles.addAll(filesSimpleRelations);
		mergeFiles.addAll(filesComplexRelations);

		logger.info(String.format("Merging %d files", mergeFiles.size()));

		List<OsmIteratorInput> mergeIteratorInputs = new ArrayList<>();
		List<OsmIterator> mergeIterators = new ArrayList<>();
		for (OsmFileInput input : mergeFiles) {
			OsmIteratorInput iteratorInput = input.createIterator(true,
					outputConfig.isWriteMetadata());
			mergeIteratorInputs.add(iteratorInput);
			mergeIterators.add(iteratorInput.getIterator());
		}

		SortedMerge merge = new SortedMerge(output.getOsmOutput(),
				mergeIterators);
		merge.run();

		for (OsmIteratorInput input : mergeIteratorInputs) {
			input.close();
		}

		output.close();
	}

	private OsmFileInput input(Path path)
	{
		return new OsmFileInput(path, inputFormat);
	}

	private OsmFileInput intermediate(Path path)
	{
		return new OsmFileInput(path, outputConfigIntermediate.getFileFormat());
	}

	private void addCompletelyContainedLeaf(Node leaf)
	{
		filesNodes.add(input(filesTreeNodes.getPath(leaf)));
		filesWays.add(input(filesTreeWays.getPath(leaf)));
		filesSimpleRelations.add(input(filesTreeSimpleRelations.getPath(leaf)));
		filesComplexRelations
				.add(input(filesTreeComplexRelations.getPath(leaf)));
	}

	private void addIntersectingLeaf(Node leaf) throws IOException
	{
		LeafQuery leafQuery = new LeafQuery(test, filesTreeNodes, filesTreeWays,
				filesTreeSimpleRelations, filesTreeComplexRelations,
				inputFormat, outputConfigIntermediate, outputConfig,
				fastRelationTests);

		tmpIndexTree++;

		String tmpFilenames = filename(tmpIndexTree);
		Path pathOutNodes = pathTmpTreeNodes.resolve(tmpFilenames);
		Path pathOutWays = pathTmpTreeWays.resolve(tmpFilenames);
		Path pathOutSimpleRelations = pathTmpTreeSimpleRelations
				.resolve(tmpFilenames);
		Path pathOutComplexRelations = pathTmpTreeComplexRelations
				.resolve(tmpFilenames);
		Path pathOutAdditionalNodes = pathTmpTreeAdditionalNodes
				.resolve(tmpFilenames);
		Path pathOutAdditionalWays = pathTmpTreeAdditionalWays
				.resolve(tmpFilenames);

		QueryResult results = leafQuery.execute(leaf, pathOutNodes, pathOutWays,
				pathOutSimpleRelations, pathOutComplexRelations,
				pathOutAdditionalNodes, pathOutAdditionalWays);

		nNodes += results.getNumNodes();
		nWays += results.getNumWays();
		nSimpleRelations += results.getNumSimpleRelations();
		nComplexRelations += results.getNumComplexRelations();

		filesNodes.add(intermediate(pathOutNodes));
		filesNodes.add(intermediate(pathOutAdditionalNodes));
		filesWays.add(intermediate(pathOutWays));
		filesWays.add(intermediate(pathOutAdditionalWays));
		filesSimpleRelations.add(intermediate(pathOutSimpleRelations));
		filesComplexRelations.add(intermediate(pathOutComplexRelations));

		logger.info(String.format("Found %d nodes", results.getNumNodes()));
		logger.info(String.format("Found %d ways", results.getNumWays()));
		logger.info(String.format("Found %d simple relations",
				results.getNumSimpleRelations()));
		logger.info(String.format("Found %d complex relations",
				results.getNumComplexRelations()));
	}

	private void addCompletelyContainedBatch(Path pathRelations, long id,
			List<OsmFileInput> filesRelations)
	{
		Path path = pathRelations.resolve(Long.toString(id));
		filesNodes.add(input(path.resolve(relationNames.getNodes())));
		filesWays.add(input(path.resolve(relationNames.getWays())));
		filesRelations.add(input(path.resolve(relationNames.getRelations())));
	}

	/*
	 * This is run on each batch of relations
	 */
	private void runRelationsQuery(boolean simple, String tmpFilenames,
			Path pathNodes, Path pathWays, Path pathRelations,
			Path pathOutNodes, Path pathOutWays, Path pathOutRelations)
			throws IOException
	{
		logger.info("loading data");
		InMemoryListDataSet dataRelations = read(pathRelations);

		dataRelations.sort();
		InMemoryListDataSet selectedRelations;
		if (relationFilter == null) {
			selectedRelations = dataRelations;
		} else {
			selectedRelations = new RelationSelector().select(relationFilter,
					dataRelations);
			selectedRelations.sort();

			logger.info(String.format("selected %d of %d relations",
					selectedRelations.getRelations().size(),
					dataRelations.getRelations().size()));
		}

		if (selectedRelations.getRelations().isEmpty()) {
			logger.info("nothing selected, skipping");
			return;
		}

		InMemoryListDataSet dataNodes = read(pathNodes);
		InMemoryListDataSet dataWays = read(pathWays);

		OsmStreamOutput outRelations = createOutput(pathOutRelations);
		RelationQueryBag queryBag = new RelationQueryBag(outRelations);

		logger.info("running query");
		// First determine all nodes of this batch that are within the
		// requested region for quick relation selection by member id
		queryNodes(dataNodes, queryBag.nodeIds);
		// Also determine all ways that reference any of the nodes selected
		// before, also for quick relation selection by member id
		queryWays(dataWays, queryBag.nodeIds, queryBag.wayIds);

		if (simple) {
			SimpleRelationsQuery simpleRelationsQuery = new SimpleRelationsQuery(
					dataNodes, dataWays, selectedRelations, test,
					fastRelationTests);
			simpleRelationsQuery.execute(queryBag);
		} else {
			ComplexRelationsQuery complexRelationsQuery = new ComplexRelationsQuery(
					dataNodes, dataWays, selectedRelations, test,
					fastRelationTests);
			complexRelationsQuery.execute(queryBag);
		}

		finish(outRelations);

		logger.info("writing nodes and ways");
		OsmStreamOutput outputNodes = createOutput(pathOutNodes);
		QueryUtil.writeNodes(queryBag.additionalNodes,
				outputNodes.getOsmOutput());
		finish(outputNodes);

		OsmStreamOutput outputWays = createOutput(pathOutWays);
		QueryUtil.writeWays(queryBag.additionalWays, outputWays.getOsmOutput());
		finish(outputWays);

		filesNodes.add(intermediate(pathOutNodes));
		filesWays.add(intermediate(pathOutWays));
		filesSimpleRelations.add(intermediate(pathOutRelations));
	}

	private void queryNodes(InMemoryListDataSet dataNodes, TLongSet nodeIds)
			throws IOException
	{
		for (OsmNode node : dataNodes.getNodes()) {
			if (test.contains(
					new Coordinate(node.getLongitude(), node.getLatitude()))) {
				nodeIds.add(node.getId());
			}
		}
	}

	private void queryWays(InMemoryListDataSet dataWays, TLongSet nodeIds,
			TLongSet wayIds) throws IOException
	{
		for (OsmWay way : dataWays.getWays()) {
			boolean in = QueryUtil.anyNodeContainedIn(way, nodeIds);
			if (!in) {
				continue;
			}
			wayIds.add(way.getId());
		}
	}

}
