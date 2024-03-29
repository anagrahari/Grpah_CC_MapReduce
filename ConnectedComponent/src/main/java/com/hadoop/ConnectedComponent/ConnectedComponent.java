package com.hadoop.ConnectedComponent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import com.hadoop.files.DeleteFiles;
import com.hadoop.files.Stats;

/** MapReduce program to extend the Kruskal's algorithm for computing connected
 * components for a given weighted graph (also computes MST in parallel)
 */
public class ConnectedComponent extends Configured implements Tool {

	// File in "/user/USER/source" on HDFS to be processed
	public static String[] filenames = {"edges1","Graph1","Graph2","Graph3"};
	public static String user = Stats.getUserName();
	public static long blockSize = 50000;

	public static final Log LOG = LogFactory.getLog("graph.connectedComp");

	static enum CCCounters {
		totalInputEdges, 
		totalWeight,
		totalOutputEdges
	}

	/**
	 * MapReduce has automatic sorting by keys after the map phase. So the
	 * reducer will get the edges in the order of increasing weight.
	 * 
	 * Input: <key, value>: NULL, WEIGHT<tab>SOURCE<tab>DESTINATION
	 * Output: <key, value>: WEIGHT, SOURCE<tab>DESTINATION
	 */
	public static class CCMapper extends
			Mapper<Object, Text, DoubleWritable, Text> {
		public void map(Object key, Text value, Context context)
				throws IOException, InterruptedException {
			Configuration conf = context.getConfiguration();
			Boolean changeKey = conf.getBoolean("changeKey",false);

			String[] inputTokens = value.toString().split("\t");

			// get the weight
			double wt = Double.parseDouble(inputTokens[0]);
			if(changeKey){
				wt += Math.random()* Math.pow(10,-9);
			}
			DoubleWritable iwWeight = new DoubleWritable(wt);

			// setting the source and destination to the key value
			Text srcDestPair = new Text();
			srcDestPair.set(inputTokens[1] + "\t" + inputTokens[2]);

			// write <key, value> to context where the key is the weight, and
			// the value is the sourceDestinationPair
			context.write(iwWeight, srcDestPair);
			context.getCounter(CCCounters.totalInputEdges).increment(1L);
		}
	}

	static class CCReducer extends Reducer<DoubleWritable, Text, Text, Text> {

		Map<Integer, Set<String>> ConnectedComp = new HashMap<Integer, Set<String>>(); 
		
		public void reduce(DoubleWritable inputKey, Iterable<Text> values,
				Context context) throws IOException, InterruptedException {

			// converting the type of inputKey to a Text
			Text outputKey = new Text(inputKey.toString());
			Configuration conf = context.getConfiguration();
			Boolean unChangeKey = conf.getBoolean("unChangeKey",false);
			
			for (Text val : values) {
				// boolean values to check if the two nodes belong to the same
				// tree, useful for cycle detection
				boolean ignoreEdgeSameSet1 = false;

				// split the srcDestination pair, to get the nodes of the edge
				String[] srcDest = val.toString().split("\t");
				String src = srcDest[0];
				String dest = srcDest[1];
				
				Set<String> nodesSet = new HashSet<String>();
				nodesSet.add(src);
				nodesSet.add(dest);

				// check conditions to ignore the edge
				ignoreEdgeSameSet1 = unionSet(nodesSet, src, dest);
				
				// if all the following three boolean values are false, then
				// adding the edge to the tree will not form a cycle
				if (!ignoreEdgeSameSet1) {
					double weight = Double.parseDouble(outputKey.toString());
					// increment the counter by the weight value
					context.getCounter(CCCounters.totalWeight).increment((long)weight);
					context.getCounter(CCCounters.totalOutputEdges).increment(1L);

					// write the weight and srcDestination pair to the output
					if(unChangeKey)	{
						outputKey = new Text(String.valueOf((long)Double.parseDouble(outputKey.toString())));
					}
					context.write(outputKey, val);
				}
			}
		}

		// method to unite the set of the two nodes - node1 and node2, this is
		// useful to add edges to the tree without forming cycles
		private boolean unionSet(Set<String> nodesSet, String node1,String node2) {
			boolean ignoreEdge = false;

			int fnode = Integer.valueOf(node1);
			int snode = Integer.valueOf(node2);

			int repNode1 = getComponent(fnode);
			int repNode2 = getComponent(snode);

			repNode1 = repNode1 != Integer.MAX_VALUE ? repNode1 : fnode;
			repNode2 = repNode2 != Integer.MAX_VALUE ? repNode2 : snode;
			int smallest, larger;
			if (repNode1 < repNode2) {
				smallest = repNode1;
				larger = repNode2;
			} 
			else {
				smallest = repNode2;
				larger = repNode1;
			}
			ignoreEdge = ConnComponent(smallest, larger, nodesSet);
			return ignoreEdge;

		}

		private boolean ConnComponent(int smallest, int larger,
				Set<String> nodesSet) {
			boolean ignoreEdge = false;
			if (!ConnectedComp.containsKey(smallest) && !ConnectedComp.containsKey(larger)) {
				ConnectedComp.put(smallest, nodesSet);
			} 
			else if (ConnectedComp.containsKey(smallest)) {
				if (ConnectedComp.get(smallest).contains(String.valueOf(larger))) {
					ignoreEdge = true;
				} 
				else if (ConnectedComp.containsKey(larger)) {
					Set<String> nodeLarger = ConnectedComp.get(larger);
					ConnectedComp.remove(larger);
					ConnectedComp.get(smallest).addAll(nodeLarger);
				} 
				else {
					ConnectedComp.get(smallest).addAll(nodesSet);
				}
			} 
			else if (ConnectedComp.containsKey(larger)) {
				ConnectedComp.put(smallest, nodesSet);
				Set<String> nodeLarger = ConnectedComp.get(larger);
				ConnectedComp.remove(larger);
				ConnectedComp.get(smallest).addAll(nodeLarger);
			}
			return ignoreEdge;
		}

		private int getComponent(int node) {
			Set<Integer> keySet = ConnectedComp.keySet();
			for (int key : keySet) {
				Set<String> nodesSet = ConnectedComp.get(key);
				if (nodesSet.contains(String.valueOf(node))) {
					return key;
				}
			}
			return Integer.MAX_VALUE;
		}

		protected void cleanup(Context context) throws IOException,
				InterruptedException {
			super.cleanup(context);
			Configuration conf = new Configuration();
			String path=context.getConfiguration().get("cc.path");
			FileSystem fs1 = FileSystem.get(URI.create(path),conf);
			BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fs1.create(new Path(path),true)));
			
			for (int comp : ConnectedComp.keySet()) {
				bw.write(comp+"\t"+ConnectedComp.get(comp));
				bw.write("\n");
			}
			bw.flush();
			bw.close();
		}
	}

	static int printUsage() {
		System.out.println("Correct Usage: ConnectedComponents filename");
		return -1;
	}
	
	private Job getJob(String[] args, int numReducer, String filename) throws IOException {

		Configuration conf = new Configuration();
		Path center = new Path("output/CC-"+filename+"-comp");
		conf.set("cc.path", center.toString());
		if(numReducer == 0)
			conf.setBoolean("changeKey", true);
		else if(numReducer == 1)
			conf.setBoolean("unChangeKey", true);
		
		Job job = new Job(conf);
		job.setJobName("CC");

		job.setNumReduceTasks(numReducer);
		job.setJarByClass(ConnectedComponent.class);
		job.setOutputKeyClass(DoubleWritable.class);
		job.setOutputValueClass(Text.class);

		job.setMapperClass(CCMapper.class);
		job.setReducerClass(CCReducer.class);
		return job;
	}

	/** Main driver for Connected Components MapReduce program */
	public int run(String[] args) throws Exception {
		boolean first_time = true;
		Configuration conf = new Configuration();
		
		for(String filename: filenames)	{
			String path="logs/CC-"+filename;
			FileSystem fs1 = FileSystem.get(URI.create(path),conf);
			BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fs1.create(new Path(path),true)));
			
			int iterationCount = 0;
			int numReducer = 0;
			int fixedNumReducer = 1;
			boolean flagStop = false;
			long lastIterationEdges = 0;
			bw.write("********************************************************\n");
			bw.write("Processing File: "+filename+"\n");
			Calendar startTime = Calendar.getInstance();
			do {
				bw.write("************Iteration: "+iterationCount+" ***********\n");
				String input;
				if (iterationCount < 1)
					input = "source/"+filename;
				else
					input = "output/CC-"+filename+"-" + iterationCount;
				
				String output = "output/CC-"+filename+"-" + (iterationCount + 1);
				Job job;
				if (first_time) {
					job = getJob(args, 0,filename);
					first_time = false;
				} else {
					job = getJob(args, fixedNumReducer,filename);
				}
				FileInputFormat.addInputPath(job, new Path(input));
				FileOutputFormat.setOutputPath(job, new Path(output));
				job.waitForCompletion(true);
	
				Counters jobCntrs = job.getCounters();
				long totalWeight = jobCntrs.findCounter(CCCounters.totalWeight).getValue();
				bw.write("Total weight of the MST: " + totalWeight+"\n");
				long inputEdges = jobCntrs.findCounter(CCCounters.totalInputEdges).getValue();
				bw.write("Total Input Edges: " + inputEdges+"\n");
	
				long totalEdges = jobCntrs.findCounter(CCCounters.totalOutputEdges).getValue();
				bw.write("Total number of reduced edges: "+ totalEdges+"\n");
				if(iterationCount == 0)
					totalEdges = inputEdges;
				numReducer = (int)Math.ceil((double)totalEdges / blockSize);
				
				if(iterationCount >= 1)
					DeleteFiles.deleteFile(user,"output","CC-"+filename+"-"+iterationCount);
				
				if(flagStop)
					break;
				if(totalEdges == lastIterationEdges)	{
					bw.write("File bigger than machine size"+"\n");
					DeleteFiles.deleteFile(user,"output","CC-"+filename+"-"+(iterationCount+1));
					return -1;
				}
				lastIterationEdges = totalEdges;
				
				if(numReducer == 1)
					flagStop = true;
				
				iterationCount++;
			} while (numReducer >= 1);
			DeleteFiles.deleteFile(user,"output","CC-"+filename+"-"+(iterationCount));
			Calendar finishTime = Calendar.getInstance();
			bw.write("Total Iterations: "+iterationCount+"\n");
			bw.write("Time Taken: "+(finishTime.getTimeInMillis()-startTime.getTimeInMillis())/1000.0+" seconds\n");
			bw.close();
		}		
		return 0;
	}

	public static void main(String[] args) throws Exception {
		boolean debug = true;
		if(args.length == 1)	{
			filenames = args[0].split(" ");
		}
		else if(args.length == 2)	{
			filenames = args[0].split(" ");
			blockSize = Integer.parseInt(args[1]);
		}
		else if(!debug)	{
			printUsage();
			return;
		}
		int res = ToolRunner.run(new Configuration(), new ConnectedComponent(), args);
		System.exit(res);
	}
}