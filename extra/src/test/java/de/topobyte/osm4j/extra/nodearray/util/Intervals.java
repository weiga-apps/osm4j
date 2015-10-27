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

package de.topobyte.osm4j.extra.nodearray.util;

import java.util.Random;

public class Intervals
{

	public static Interval LONGITUDE = new Interval(-180, 180);
	public static Interval LATITUDE = new Interval(-90, 90);

	private static Random random = new Random();

	public static double random(Interval interval)
	{
		return interval.getMin() + random.nextDouble() * interval.getSize();
	}

}
