/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.runtime.compress;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.compress.readers.ReaderColumnSelection;
import org.apache.sysds.runtime.compress.readers.ReaderColumnSelectionBitSet;
import org.apache.sysds.runtime.compress.utils.ABitmap;
import org.apache.sysds.runtime.compress.utils.Bitmap;
import org.apache.sysds.runtime.compress.utils.BitmapLossy;
import org.apache.sysds.runtime.compress.utils.DblArray;
import org.apache.sysds.runtime.compress.utils.DblArrayIntListHashMap;
import org.apache.sysds.runtime.compress.utils.DblArrayIntListHashMap.DArrayIListEntry;
import org.apache.sysds.runtime.compress.utils.DoubleIntListHashMap;
import org.apache.sysds.runtime.compress.utils.DoubleIntListHashMap.DIListEntry;
import org.apache.sysds.runtime.compress.utils.IntArrayList;
import org.apache.sysds.runtime.data.SparseBlock;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;

/**
 * Static functions for encoding bitmaps in various ways.
 */
public class BitmapEncoder {

	private static final Log LOG = LogFactory.getLog(BitmapEncoder.class.getName());

	/**
	 * Generate uncompressed bitmaps for a set of columns in an uncompressed matrix block.
	 * 
	 * @param colIndices Indexes (within the block) of the columns to extract
	 * @param rawBlock   An uncompressed matrix block; can be dense or sparse
	 * @param transposed Boolean specifying if the rawblock was transposed.
	 * @return uncompressed bitmap representation of the columns
	 */
	public static ABitmap extractBitmap(int[] colIndices, MatrixBlock rawBlock, boolean transposed) {
		// note: no sparse column selection reader because low potential
		// single column selection
		Bitmap res = null;
		if(colIndices.length == 1) {
			res = extractBitmap(colIndices[0], rawBlock, transposed);
		}
		// multiple column selection (general case)
		else {
			try {
				res = extractBitmap(colIndices, ReaderColumnSelection.createReader(rawBlock, colIndices, transposed));
			}
			catch(Exception e) {
				throw new DMLRuntimeException("Failed to extract bitmap", e);
			}
		}
		return res;

	}

	public static ABitmap extractBitmap(int[] colIndices, int rows, BitSet rawBlock, CompressionSettings compSettings) {
		ReaderColumnSelection reader = new ReaderColumnSelectionBitSet(rawBlock, rows, colIndices);
		Bitmap res = extractBitmap(colIndices, reader);
		return res;
	}

	/**
	 * Extract Bitmap from a single column.
	 * 
	 * It counts the instances of zero, but skips storing the values.
	 * 
	 * @param colIndex   The index of the column
	 * @param rawBlock   The Raw matrix block (that can be transposed)
	 * @param transposed Boolean specifying if the rawBlock is transposed or not.
	 * @return Bitmap containing the Information of the column.
	 */
	private static Bitmap extractBitmap(int colIndex, MatrixBlock rawBlock, boolean transposed) {
		DoubleIntListHashMap hashMap = transposed ? extractHashMapTransposed(colIndex,
			rawBlock) : extractHashMap(colIndex, rawBlock);
		return makeBitmap(hashMap);
	}

	private static DoubleIntListHashMap extractHashMap(int colIndex, MatrixBlock rawBlock) {
		// probe map for distinct items (for value or value groups)
		DoubleIntListHashMap distinctVals = new DoubleIntListHashMap();

		// scan rows and probe/build distinct items
		final int m = rawBlock.getNumRows();

		if((rawBlock.getNumRows() == 1 || rawBlock.getNumColumns() == 1) && !rawBlock.isInSparseFormat()) {
			double[] values = rawBlock.getDenseBlockValues();
			if(values != null)
				for(int i = 0; i < values.length; i++) {
					double val = values[i];
					if(val != 0) {
						distinctVals.appendValue(val, i);
					}
				}
		}
		else if(!rawBlock.isInSparseFormat() && rawBlock.getDenseBlock().blockSize() == 1) {
			double[] values = rawBlock.getDenseBlockValues();
			for(int i = 0, off = colIndex;
				off < rawBlock.getNumRows() * rawBlock.getNumColumns();
				i++, off += rawBlock.getNumColumns()) {
				double val = values[off];
				if(val != 0) {
					distinctVals.appendValue(val, i);
				}
			}
		}
		else // GENERAL CASE
		{
			for(int i = 0; i < m; i++) {
				double val = rawBlock.quickGetValue(i, colIndex);
				if(val != 0) {
					distinctVals.appendValue(val, i);
				}
			}
		}
		return distinctVals;
	}

	private static DoubleIntListHashMap extractHashMapTransposed(int colIndex, MatrixBlock rawBlock) {
		// probe map for distinct items (for value or value groups)
		DoubleIntListHashMap distinctVals = new DoubleIntListHashMap();

		// scan rows and probe/build distinct items
		final int m = rawBlock.getNumColumns();

		if(rawBlock.isInSparseFormat()) { // SPARSE and Transposed.
			SparseBlock a = rawBlock.getSparseBlock();
			if(a != null && !a.isEmpty(colIndex)) {
				int apos = a.pos(colIndex);
				int alen = a.size(colIndex);
				int[] aix = a.indexes(colIndex);
				double[] avals = a.values(colIndex);

				for(int j = apos; j < apos + alen; j++) {
					distinctVals.appendValue(avals[j], aix[j]);
				}
			}
		}
		else if((rawBlock.getNumRows() == 1 || rawBlock.getNumColumns() == 1) && !rawBlock.isInSparseFormat()) {
			double[] values = rawBlock.getDenseBlockValues();
			if(values != null) {
				for(int i = 0; i < values.length; i++) {
					double val = values[i];
					if(val != 0) {
						distinctVals.appendValue(val, i);
					}
				}
			}
		}
		else // GENERAL CASE
		{
			for(int i = 0; i < m; i++) {
				double val = rawBlock.quickGetValue(colIndex, i);
				if(val != 0) {
					distinctVals.appendValue(val, i);
				}
			}
		}
		return distinctVals;
	}

	/**
	 * Extract Bitmap from multiple columns together.
	 * 
	 * It counts the instances of rows containing only zero values, but other groups can contain a zero value.
	 * 
	 * @param colIndices The Column indexes to extract the multi-column bit map from.
	 * @param rowReader  A Reader for the columns selected.
	 * @return The Bitmap
	 */
	private static Bitmap extractBitmap(int[] colIndices, ReaderColumnSelection rowReader) {
		// probe map for distinct items (for value or value groups)

		DblArrayIntListHashMap distinctVals;
		if(colIndices.length > 10) {
			distinctVals = new DblArrayIntListHashMap(2048);
		}
		else {
			distinctVals = new DblArrayIntListHashMap();
		}

		// scan rows and probe/build distinct items
		DblArray cellVals = null;

		int zero = 0;
		while((cellVals = rowReader.nextRow()) != null) {
			if(cellVals.getData() == null) {
				zero += 1;
			}
			else {
				IntArrayList lstPtr = distinctVals.get(cellVals);
				if(lstPtr == null) {
					// create new objects only on demand
					lstPtr = new IntArrayList();
					distinctVals.appendValue(new DblArray(cellVals), lstPtr);
				}
				lstPtr.appendValue(rowReader.getCurrentRowIndex());
			}
		}
		return makeBitmap(distinctVals, zero, colIndices.length);
	}

	/**
	 * Make the multi column Bitmap.
	 * 
	 * @param distinctVals The distinct values found in the columns selected.
	 * @param numZeros     Number of zero rows. aka rows only containing zero values.
	 * @param numCols      Number of columns
	 * @return The Bitmap.
	 */
	private static Bitmap makeBitmap(DblArrayIntListHashMap distinctVals, int numZeros, int numCols) {
		// added for one pass bitmap construction
		// Convert inputs to arrays
		ArrayList<DArrayIListEntry> mapEntries = distinctVals.extractValues();
		if(!mapEntries.isEmpty()) {

			int numVals = distinctVals.size();
			double[] values = new double[numVals * numCols];
			IntArrayList[] offsetsLists = new IntArrayList[numVals];
			int bitmapIx = 0;
			for(DArrayIListEntry val : mapEntries) {
				System.arraycopy(val.key.getData(), 0, values, bitmapIx * numCols, numCols);
				offsetsLists[bitmapIx++] = val.value;
			}

			return new Bitmap(numCols, offsetsLists, numZeros, values);
		}
		else {
			return new Bitmap(numCols, null, numZeros, null);
		}

	}

	/**
	 * Make single column bitmap.
	 * 
	 * @param distinctVals Distinct values contained in the bitmap, mapping to offsets for locations in the matrix.
	 * @param numZeros     Number of zero values in the matrix
	 * @return The single column Bitmap.
	 */
	private static Bitmap makeBitmap(DoubleIntListHashMap distinctVals) {
		// added for one pass bitmap construction
		// Convert inputs to arrays
		int numVals = distinctVals.size();
		double[] values = new double[numVals];
		IntArrayList[] offsetsLists = new IntArrayList[numVals];
		int bitmapIx = 0;
		for(DIListEntry val : distinctVals.extractValues()) {
			values[bitmapIx] = val.key;
			offsetsLists[bitmapIx++] = val.value;
		}

		return new Bitmap(1, offsetsLists, 1, values);
	}

	/**
	 * Given a Bitmap try to make a lossy version of the same bitmap.
	 * 
	 * @param ubm The Uncompressed version of the bitmap.
	 * @return A bitmap.
	 */
	public static ABitmap makeBitmapLossy(Bitmap ubm) {
		final double[] fp = ubm.getValues();
		if(fp.length == 0) {
			return ubm;
		}
		Stats stats = new Stats(fp);
		// TODO make better decisions than just a 8 Bit encoding.
		if(Double.isInfinite(stats.max) || Double.isInfinite(stats.min)) {
			LOG.warn("Defaulting to incompressable colGroup");
			return ubm;
		}
		else {
			return make8BitLossy(ubm, stats);
		}
	}

	/**
	 * Make the specific 8 bit encoding version of a bitmap.
	 * 
	 * @param ubm   The uncompressed Bitmap.
	 * @param stats The statistics associated with the bitmap.
	 * @return a lossy bitmap.
	 */
	private static BitmapLossy make8BitLossy(Bitmap ubm, Stats stats) {
		final double[] fp = ubm.getValues();
		int numCols = ubm.getNumColumns();
		double scale = Math.max(Math.abs(stats.min), stats.max) / (double) Byte.MAX_VALUE;
		byte[] scaledValues = scaleValues(fp, scale);
		if(numCols == 1) {
			return makeBitmapLossySingleCol(ubm, scaledValues, scale);
		}
		else {
			return makeBitmapLossyMultiCol(ubm, scaledValues, scale);
		}
	}

	/**
	 * Make Single column lossy bitmap.
	 * 
	 * This method merges the previous offset lists together to reduce the size.
	 * 
	 * @param ubm          The original uncompressed bitmap.
	 * @param scaledValues The scaled values to map into.
	 * @param scale        The scale in use.
	 * @return The Lossy bitmap.
	 */
	private static BitmapLossy makeBitmapLossySingleCol(Bitmap ubm, byte[] scaledValues, double scale) {

		// Using Linked Hashmap to preserve the sorted order.
		Map<Byte, Queue<IntArrayList>> values = new LinkedHashMap<>();
		Map<Byte, Integer> lengths = new HashMap<>();

		IntArrayList[] fullSizeOffsetsLists = ubm.getOffsetList();
		int numZeroGroups = ubm.getZeroCounts();
		boolean somethingToMerge = false;

		for(int idx = 0; idx < scaledValues.length; idx++) {
			if(scaledValues[idx] != 0) { // Throw away zero values.
				if(values.containsKey(scaledValues[idx])) {
					values.get(scaledValues[idx]).add(fullSizeOffsetsLists[idx]);
					lengths.put(scaledValues[idx], lengths.get(scaledValues[idx]) + fullSizeOffsetsLists[idx].size());
					somethingToMerge = true;
				}
				else {
					Queue<IntArrayList> offsets = new LinkedList<>();
					offsets.add(fullSizeOffsetsLists[idx]);
					values.put(scaledValues[idx], offsets);
					lengths.put(scaledValues[idx], fullSizeOffsetsLists[idx].size());
				}
			}
			else {
				numZeroGroups++;
			}
		}

		if(somethingToMerge) {
			byte[] scaledValuesReduced = new byte[values.keySet().size()];
			IntArrayList[] newOffsetsLists = new IntArrayList[values.keySet().size()];
			Iterator<Entry<Byte, Queue<IntArrayList>>> x = values.entrySet().iterator();
			int idx = 0;
			while(x.hasNext()) {
				Entry<Byte, Queue<IntArrayList>> ent = x.next();
				scaledValuesReduced[idx] = ent.getKey().byteValue();
				Queue<IntArrayList> q = ent.getValue();
				if(q.size() == 1) {
					newOffsetsLists[idx] = q.remove();
				}
				else {
					newOffsetsLists[idx] = mergeOffsets(q, new int[lengths.get(ent.getKey())]);
				}
				idx++;
			}
			return new BitmapLossy(ubm.getNumColumns(), newOffsetsLists, numZeroGroups, scaledValuesReduced, scale);
		}
		else {
			return new BitmapLossy(ubm.getNumColumns(), fullSizeOffsetsLists, numZeroGroups, scaledValues, scale);
		}
	}

	/**
	 * Multi column instance of makeBitmapLossySingleCol
	 * 
	 * @param ubm          The original uncompressed bitmap.
	 * @param scaledValues The scaled values to map into.
	 * @param scale        The scale in use.
	 * @return The Lossy bitmap.
	 */
	private static BitmapLossy makeBitmapLossyMultiCol(Bitmap ubm, byte[] scaledValues, double scale) {
		int numColumns = ubm.getNumColumns();
		Map<List<Byte>, Queue<IntArrayList>> values = new HashMap<>();
		Map<List<Byte>, Integer> lengths = new HashMap<>();
		IntArrayList[] fullSizeOffsetsLists = ubm.getOffsetList();
		int numZeroGroups = ubm.getZeroCounts();
		boolean allZero = true;
		boolean somethingToMerge = false;
		for(int idx = 0; idx < scaledValues.length; idx += numColumns) {
			List<Byte> array = new ArrayList<>();
			for(int off = 0; off < numColumns; off++) {
				allZero = scaledValues[idx + off] == 0 && allZero;
				array.add(scaledValues[idx + off]);
			}

			numZeroGroups += allZero ? 1 : 0;
			if(!allZero) {
				IntArrayList entry = fullSizeOffsetsLists[idx / numColumns];
				if(values.containsKey(array)) {
					values.get(array).add(entry);
					lengths.put(array, lengths.get(array) + entry.size());
					somethingToMerge = true;
				}
				else {
					Queue<IntArrayList> offsets = new LinkedList<>();
					offsets.add(entry);
					values.put(array, offsets);
					lengths.put(array, entry.size());
				}
			}
			allZero = true;
		}

		// HACK; we make sure that the first sparse unsafe operation assume
		// that we have entries with zero values. This makes the first sparse
		// unsafe operation slightly slower, if the input compressed matrix is
		// fully dense, aka containing no zero values.
		// This is required for multi-column colGroups.
		numZeroGroups = numZeroGroups + 1;

		if(somethingToMerge) {

			byte[] scaledValuesReduced = new byte[values.keySet().size() * numColumns];
			IntArrayList[] newOffsetsLists = new IntArrayList[values.keySet().size()];
			Iterator<Entry<List<Byte>, Queue<IntArrayList>>> x = values.entrySet().iterator();
			int idx = 0;
			while(x.hasNext()) {
				Entry<List<Byte>, Queue<IntArrayList>> ent = x.next();
				List<Byte> key = ent.getKey();
				int row = idx * numColumns;
				for(int off = 0; off < numColumns; off++) {
					scaledValuesReduced[row + off] = key.get(off);
				}
				Queue<IntArrayList> q = ent.getValue();
				if(q.size() == 1) {
					newOffsetsLists[idx] = q.remove();
				}
				else {
					newOffsetsLists[idx] = mergeOffsets(q, new int[lengths.get(key)]);
				}
				idx++;
			}

			return new BitmapLossy(ubm.getNumColumns(), newOffsetsLists, numZeroGroups, scaledValuesReduced, scale);
		}
		else {
			return new BitmapLossy(ubm.getNumColumns(), fullSizeOffsetsLists, numZeroGroups, scaledValues, scale);
		}
	}

	/**
	 * Merge method to join together offset lists.
	 * 
	 * @param offsets The offsets to join
	 * @param res     The result int array to put the values into. This has to be allocated to the joined size of all
	 *                the input offsetLists
	 * @return The merged offsetList.
	 */
	private static IntArrayList mergeOffsets(Queue<IntArrayList> offsets, int[] res) {
		int indexStart = 0;
		while(!offsets.isEmpty()) {
			IntArrayList h = offsets.remove();
			int[] v = h.extractValues();
			for(int i = 0; i < h.size(); i++) {
				res[indexStart++] = v[i];
			}
		}
		Arrays.sort(res);
		return new IntArrayList(res);
	}

	/**
	 * Utility method to scale all the values in the array to byte range
	 * 
	 * @param fp    double array to scale
	 * @param scale the scale to apply
	 * @return the scaled values in byte
	 */
	private static byte[] scaleValues(double[] fp, double scale) {
		byte[] res = new byte[fp.length];
		for(int idx = 0; idx < fp.length; idx++) {
			res[idx] = (byte) (Math.round(fp[idx] / scale));
		}
		return res;
	}

	/**
	 * Statistics class to analyse what compression plan to use.
	 */
	private static class Stats {
		protected double max;
		protected double min;
		protected double minDelta;
		protected double maxDelta;
		protected boolean sameDelta;

		public Stats(double[] fp) {
			max = Double.NEGATIVE_INFINITY;
			min = Double.POSITIVE_INFINITY;
			maxDelta = Double.NEGATIVE_INFINITY;
			minDelta = Double.POSITIVE_INFINITY;
			sameDelta = true;
			if(fp.length > 1) {

				double delta = fp[0] - fp[1];
				for(int i = 0; i < fp.length - 1; i++) {
					if(fp[i] > max)
						max = fp[i];
					if(fp[i] < min)
						min = fp[i];
					double ndelta = fp[i] - fp[i + 1];
					if(delta < minDelta) {
						minDelta = delta;
					}
					if(delta > maxDelta) {
						maxDelta = delta;
					}
					if(sameDelta && Math.abs(delta - ndelta) <= delta * 0.00000001) {
						sameDelta = false;
					}
					delta = ndelta;
				}
				if(fp[fp.length - 1] > max)
					max = fp[fp.length - 1];
				if(fp[fp.length - 1] < min)
					min = fp[fp.length - 1];
			}
			else {
				max = fp[0];
				min = fp[0];
				maxDelta = 0;
				minDelta = 0;
			}
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append("Stats{" + this.hashCode() + "}");
			sb.append(" max: " + max);
			sb.append(" min: " + min);
			sb.append(" minΔ: " + minDelta);
			sb.append(" maxΔ: " + maxDelta);
			sb.append(" sameΔ: " + maxDelta);
			return sb.toString();
		}

	}
}
