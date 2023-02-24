import org.apache.hadoop.conf.Configuration;
import java.io.BufferedWriter;
import javax.naming.Context;
import java.io.File;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.*;
import org.apache.hadoop.mapreduce.lib.input.MultipleInputs;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.yarn.webapp.hamlet2.Hamlet;
import software.amazon.awssdk.regions.Region;
import utils.DependencyTree;
import utils.FeaturesVectorLength;
import utils.PairOfNouns;
import utils.TreePattern;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;

/***
 * * The PatternParser job is responsible for the following:
 *     1. (Mapper) Parse each sentence to a dependency tree
 *     2. (Mapper) For each path in the dependency tree emit : <pattern, noun_pair>.
 *     3. (Reducer) Check for each pattern if there are less distinct noun pairs than dpmin.
 */

public class PatternParser {
    public static class MapperClass extends Mapper<LongWritable, Text, Text, PairOfNouns> {
        @Override
        public void map(LongWritable lineId, Text line, Context context) throws IOException, InterruptedException {
            DependencyTree tree = new DependencyTree(line.toString());
            for (TreePattern tp : tree.patterns()) {
                Text text = new Text(tp.getPattern());
                context.write(text, tp.getPair());
            }
        }
    }

    public static class ReducerClass extends Reducer<Text, PairOfNouns, PairOfNouns, IntWritable> {
        private int curr_dpMin;
        private int i;

        @Override
        protected void setup(Context context) throws IOException, InterruptedException {
            super.setup(context);
            curr_dpMin = Integer.parseInt(context.getConfiguration().get("dpMin"));
        }

        @Override
        public void reduce(Text key, Iterable<PairOfNouns> values, Context context) throws IOException, InterruptedException {
            HashSet<String> set = new HashSet<>(curr_dpMin);
            ArrayList<PairOfNouns> list = new ArrayList<>();
            for (PairOfNouns pair : values) {
                // System.err.println(" PAIR IS: " + nounPair.toString()); // todo : delete?
                list.add(new PairOfNouns(new Text(pair.getWord1().toString()), new Text(pair.getWord2().toString()), pair.getTotal()));
                if (!set.contains(pair.getWord1().toString()+ " "+ pair.getWord2().toString()))
                    set.add(pair.getWord1().toString()+ " "+ pair.getWord2().toString());
            }
            // System.err.println("START INDEX " + i + " key " + key + "values size " + cache.size() + " set size " + set.size());
            // todo : delete?
            if(checkDPmin(curr_dpMin, set, list, context, i, key)) {
                i+=1;
            }
        }

        public boolean checkDPmin (int dpmin, HashSet hashSet, ArrayList<PairOfNouns> arrayList, Context context, int index, Text key) throws IOException, InterruptedException {
            boolean changed = false;
            if (hashSet.size() >= dpmin) {
                changed = true;
                for(PairOfNouns pairOfNouns : arrayList) {
                    context.write(pairOfNouns, new IntWritable(index));
                }
                updateOutput(key.toString());
            }
            return changed;
        }

        private boolean updateOutput (String feature) {
            java.nio.file.Path pathOfLog = Paths.get("features.txt");
            BufferedWriter bw = null;
            try {
                bw = Files.newBufferedWriter(pathOfLog, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                bw.append(feature+"\n");
                bw.close();
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }


        // todo : check if necessary because of adding the Counter

         //@Override
        /*protected void cleanup(Context context) throws IOException, InterruptedException {
            super.cleanup(context);
            try {
                String totals_time = context.getConfiguration().get("TOTALS_TIME");
                String file_name = "total_" + index + "_" + totals_time;
                System.err.println("Uploading file name:" + file_name);
                File file = new File(file_name);
                file.createNewFile();
                S3Client s3 = S3Client.builder().region(Region.US_EAST_1).build();
                s3.putObject(PutObjectRequest.builder().acl(ObjectCannedACL.PUBLIC_READ_WRITE)
                                .bucket(context.getConfiguration().get("BUCKET_NAME"))
                                .key("totals/" + file_name)
                                .build()
                        , RequestBody.fromFile(new File(file_name)));
                file.delete();

                s3.putObject(PutObjectRequest.builder().acl(ObjectCannedACL.PUBLIC_READ_WRITE)
                                .bucket(context.getConfiguration().get("BUCKET_NAME"))
                                .key("features"+this.dpMin+".txt")
                                .build()
                        , RequestBody.fromFile(new File("features.txt")));
            } catch (Exception e) {
                System.err.println(e.getMessage());
            }
        }*/
    }


    public static void main(String[] args) throws Exception {
        Configuration conf = new Configuration();
        conf.set("dpMin", args[1]); // TODO: see reference in FeaturesVectorBuilder line 93 for extract this data
        Job job = Job.getInstance(conf, "PatternParser");
        job.setJarByClass(PatternParser.class);
        job.setNumReduceTasks(1);
        job.setReducerClass(ReducerClass.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(PairOfNouns.class);
        job.setOutputKeyClass(PairOfNouns.class);
        job.setOutputValueClass(IntWritable.class);
        MultipleInputs.addInputPath(job,new Path(MainLogic.BUCKET_PATH + "training_input1"), TextInputFormat.class, //
                // TODO: change the training file name?
                MapperClass.class);
        MultipleInputs.addInputPath(job,new Path(MainLogic.BUCKET_PATH + "training_input2"), TextInputFormat.class,
                MapperClass.class);                 // TODO: change the training file name?
        FileOutputFormat.setOutputPath(job, new Path(MainLogic.BUCKET_PATH + "/Step1"));
        job.setOutputFormatClass(SequenceFileOutputFormat.class);

        if (job.waitForCompletion(true)) {
            Counters counters = job.getCounters();
            // todo : field for counter ?
            Counter counter = counters.findCounter(PatternParser.ReducerClass.Counter.N); // TODO: change
            FeaturesVectorLength.getInstance().setLength((int) counter.getValue());
            System.exit(0);
        }
        System.exit(1);    }
}