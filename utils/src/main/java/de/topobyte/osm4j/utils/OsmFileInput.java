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

package de.topobyte.osm4j.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import de.topobyte.osm4j.core.access.OsmIterator;
import de.topobyte.osm4j.core.access.OsmReader;

public class OsmFileInput implements OsmAccessFactory
{

	private Path path;
	private FileFormat fileFormat;

	public OsmFileInput(OsmFile osmFile)
	{
		this.path = osmFile.getPath();
		this.fileFormat = osmFile.getFileFormat();
	}

	public OsmFileInput(Path path, FileFormat fileFormat)
	{
		this.path = path;
		this.fileFormat = fileFormat;
	}

	@Override
	public OsmIteratorInput createIterator(boolean readMetadata)
			throws IOException
	{
		InputStream input = StreamUtil.bufferedInputStream(path.toFile());
		OsmIterator iterator = OsmIoUtils.setupOsmIterator(input, fileFormat,
				readMetadata);
		return new OsmSingleIteratorInput(input, iterator);
	}

	@Override
	public OsmReaderInput createReader(boolean readMetadata) throws IOException
	{
		InputStream input = StreamUtil.bufferedInputStream(path.toFile());
		OsmReader reader = OsmIoUtils.setupOsmReader(input, fileFormat,
				readMetadata);
		return new OsmSingleReaderInput(input, reader);
	}

}
