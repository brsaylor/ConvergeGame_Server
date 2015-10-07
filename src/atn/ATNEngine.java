package atn;
/* By Justina Cotter
Modified By Hunvil Rodrigues

WoB ATN implements the ATN model locally using Bulirsch-Stroer integration
and outputs original WebServices data and locally generated data at the ecosystem  
and species level to evaluate the results.

Relies on WoB_Server source code for objects that store simulation timesteps and
species information.
*/

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;









//WOB_Server imports
import db.SimJobDAO;
import metadata.Constants;
import simulation.SimulationException;
import simulation.SpeciesZoneType;
import simulation.simjob.SimJob;
import model.SpeciesType;
import core.GameServer;

import java.util.HashMap;
import java.util.Map;

import javax.swing.JFrame;

import simulation.simjob.ConsumeMap;
import simulation.simjob.EcosystemTimesteps;
import simulation.simjob.NodeTimesteps;
import simulation.simjob.PathTable;
import simulation.simjob.SimJobSZT;
import util.CSVParser;
import util.Log;

/**
*
* @author Justina
*/
public class ATNEngine {

   private static UserInput userInput;
   public static Properties propertiesConfig;
   private PrintStream psATN = null;
   /*
    The first two timesteps values produced by WebServices do not
    fit the local solution well.  Therefore, these values have been excluded
    for comparison purposes
    */
   private int initTimeIdx = 0;
   private double maxBSIErr = 1.0E-3;
   private double timeIntvl = 0.1;
   private static final int biomassScale = 1000;
   private static boolean LOAD_SIM_TEST_PARAMS = false;
   private static int equationSet = 0;  //0=ATN; 1=ODE 1; 2=ODE 2
   private double initTime = 0.0;
   private double initVal = 0.0;  //for non-ATN test
	private String atnManipulationId;
	private SimJob currentSimJob;
	private int status = Constants.STATUS_FAILURE;

   public ATNEngine() {
       //load properties file containing ATN model parameter values
       propertiesConfig = new Properties();
       try {
           propertiesConfig.load(new FileInputStream(
                   "src/atn/SimJobConfig.properties"));
       } catch (FileNotFoundException ex) {
           Logger.getLogger(ATNEngine.class.getName()).log(
                   Level.SEVERE, null, ex);
       } catch (IOException ex) {
           Logger.getLogger(ATNEngine.class.getName()).log(
                   Level.SEVERE, null, ex);
       }

       /* 
        Read in non-std variables used for running sim jobs
        */
       if(LOAD_SIM_TEST_PARAMS){
	       GameServer.getInstance();
	       SpeciesType.loadSimTestNodeParams(Constants.ECOSYSTEM_TYPE);
	       SpeciesType.loadSimTestLinkParams(Constants.ECOSYSTEM_TYPE);
       }
       //Above is not needed SimJobManager does this
   }
   
	public void setATNManipulationId(String atnManipId) {
		this.atnManipulationId = atnManipId;
	}

	public void setSimJob(SimJob job) {
		this.currentSimJob = job;
	} 

   //loop through current job/results, assembling dataset
   private void genSpeciesDataset(SimJob job,
           EcosystemTimesteps ecosysTimesteps,
           Map<Integer, NodeRelationships> ecosysRelationships
   ) {
       //calc information relevant to entire ecosystem
       int speciesCnt = ecosysTimesteps.getNodeList().size();
       int timesteps = ecosysTimesteps.getTimesteps();

       //read in link parameters; this was explicitly configured to allow
       //manipulation of link parameter values, but no manipulation is 
       //performed in this version
       LinkParams lPs = new LinkParams(propertiesConfig);

       //loop through node values and assemble summary data
       int[] speciesID = new int[speciesCnt];
       SimJobSZT[] sztArray = new SimJobSZT[speciesCnt];
       double[][] webServicesData = new double[speciesCnt][timesteps];
       int spNum = 0;
       for (NodeTimesteps nodeTimesteps : ecosysTimesteps.getTimestepMapValues()) {
           SimJobSZT sjSzt = job.getSpeciesZoneByNodeId(nodeTimesteps.getNodeId());
           sztArray[spNum] = sjSzt;
           speciesID[spNum] = sjSzt.getNodeIndex();
           //copy nodetimestep data to local array for easier access
           System.arraycopy(
                   nodeTimesteps.getBiomassArray(),
                   0,
                   webServicesData[spNum],
                   0,
                   timesteps
           );

           spNum++;
       }

       //define objects to track species' contributions
       double[][][] contribs = new double[timesteps][speciesCnt][speciesCnt];
       double[][] calcBiomass = new double[timesteps][speciesCnt];
       double[][] contribsT; //current timestep

       //note: WebServices ATN Model uses B0 with default = 0.5.  This presumes
       //that biomasses are small, i.e. < 1.0.  Division by biomassScale
       //here is consistent with usage in WoB_Server.SimulationEngine to 
       //normalize biomasses.
       //need to store bm as it varies over time through integration; 
       //start with initial bm for each species
       double[] currBiomass = new double[speciesCnt];
       double[] hjrCurrBiomass = new double[speciesCnt];
       for (int i = 0; i < speciesCnt; i++) {
    	   NodeTimesteps nodeTimeSteps = ecosysTimesteps.getTimestepMap().get(speciesID[i]);
           //manually set biomass vals for excluded initial timesteps; this
           //includes the first value to be used as input 
//           for (int t = 0; t <= initTimeIdx; t++) {
//               calcBiomass[t][i] = webServicesData[i][t] / biomassScale;
//           }
//           //set first value to be used as input
//           currBiomass[i] = calcBiomass[initTimeIdx][i];
//           currBiomass[i] = calcBiomass[0][i]; //HJR
           currBiomass[i] = nodeTimeSteps.getBiomass(initTimeIdx)/biomassScale;
    	   calcBiomass[0][i] =  currBiomass[i];
       }

       //create integration object
       boolean isTest = false;
       BulirschStoerIntegration bsi = new BulirschStoerIntegration(
               timeIntvl,
               speciesID,
               sztArray,
               ecosysRelationships,
               lPs,
               maxBSIErr,
               equationSet
       );

       //calculate delta-biomass and biomass "contributions" from each related
       //species
       for (int t = initTimeIdx + 1; t < timesteps; t++) {
           boolean success = bsi.performIntegration(time(initTime, t), currBiomass);
           if (!success) {
               System.out.printf("Integration failed to converge, t = %d\n", t);
               System.out.print(bsi.extrapArrayToString(biomassScale));
               break;
           }
           currBiomass = bsi.getYNew();
           System.arraycopy(currBiomass, 0, calcBiomass[t], 0, speciesCnt);

           contribsT = bsi.getContribs();
           for (int i = 0; i < speciesCnt; i++) {
               System.arraycopy(contribsT[i], 0, contribs[t - 1][i], 0, speciesCnt);
           }

       }  //timestep loop

       //output data
       //A. print header
       psATN.printf("timesteps");
       for (int i = 0; i < timesteps; i++) {
           psATN.printf(",%d", i);
       }
       psATN.println();

       //loop through each species
       for (int i = 0; i < speciesCnt; i++) {
           psATN.printf("i.%d.sim", speciesID[i]);

           //B. print WebServices simulation data for species
           for (int t = 0; t < timesteps; t++) {
               psATN.printf(",%9.0f", webServicesData[i][t]);
           }
           psATN.println();

           //C. print combined biomass contributions (i.e. locally calculated biomass)
           //for current species.
           psATN.printf("i.%d.calc", speciesID[i]);
           for (int t = 0; t < timesteps; t++) {
               psATN.printf(",%9.0f", calcBiomass[t][i] * biomassScale);
           }
           psATN.println();

           //D. print individual biomass contributions from other species
           for (int j = 0; j < speciesCnt; j++) {
               psATN.printf("i.%d.j.%d.", speciesID[i], speciesID[j]);
               for (int t = 0; t < timesteps; t++) {
                   psATN.printf(",%9.0f", contribs[t][i][j] * biomassScale);
               }
               psATN.println();
           }
       }

   }

   /*
   Test run for integration using problem with known solution:
   equationSet 1:
   y' = (-y sin x + 2 tan x) y
   y(pi/6) = 2 / sqrt(3)
   exact solution: y(x) = 1 / cos x
   
   equationSet 2:
   y' = -200x * y^2
   y(0) = 1
   y(x) = 1 / (1 + 100x^2)
   
   */
   private void genODETestDataset() {
       int timesteps = 20;
       //setup values depending on ODE selected
       switch (equationSet) {
           case 1:
               initTime = Math.PI / 6.0;
               initVal = 2.0 / Math.sqrt(3.0);
               timeIntvl = 0.2;
               break;
           case 2:
               initTime = 0.0;
               initVal = 1.0;
               timeIntvl = 0.02;
               break;
           default:
               break;
       }
       
       double[][] bsiSoln = new double[timesteps][1];
       bsiSoln[0][0] = initVal;

       initTimeIdx = 0;
       maxBSIErr = 1.0E-3;

       initOutputStreams();

       //create integration object
       BulirschStoerIntegration bsi = new BulirschStoerIntegration(
               timeIntvl,
               new int[1],  //needed to calc number of elements
               null,
               null,
               null,
               maxBSIErr,
               equationSet
       );

       //calculate integration solution
       double[] currVal = new double[1];
       currVal[0] = bsiSoln[0][0];
       for (int t = initTimeIdx + 1; t < timesteps; t++) {
           boolean success = bsi.performIntegration(time(initTime, t - 1), currVal);
           if (!success) {
               System.out.printf("Integration failed to converge, t = %d\n", t);
               System.out.print(bsi.extrapArrayToString(1));
               break;
           }
           currVal[0] = bsi.getYNew()[0];
           bsiSoln[t][0] = currVal[0];
       }  //timestep loop

       //output data
       //A. print header
       psATN.printf("timesteps");
       for (int t = 0; t < timesteps; t++) {
           psATN.printf(",% 9.2f", time(initTime, t));
       }
       psATN.println();

       //B. print true solution: y(x) = 1 / cos x
       for (int t = 0; t < timesteps; t++) {
           //psATN.printf(",%9.2f", 1.0 / Math.cos (time(initTime, t))); //needs t++
           psATN.printf(",% 9.2f", 1.0 / 
                   (1.0 + 100.0 * Math.pow (time(initTime, t), 2.0))
           );
           
       }
       psATN.println();

       //C. print bsi solution
       for (int t = 0; t < timesteps; t++) {
           psATN.printf(",% 9.2f", bsiSoln[t][0]);
       }
       psATN.println();

   }
   
   private double time (double initTime, int t) {
       return initTime + (double) t * timeIntvl;
   }

   //loop through jobs/results, assembling dataset
   private void processJobs() {
       List<Integer> simJobs = null;

       System.out.println("Reading job IDs and initializing output files...");
       try {
           //inclusion is dictated by "include" field in table
           //typically, I would only include one or two jobs
           simJobs = SimJobDAO.getJobIdsToInclude("");
           Log.consoleln("SimJobs to be included " + simJobs.toString());
       } catch (SQLException ex) {
           Logger.getLogger(ATNEngine.class
                   .getName()).log(Level.SEVERE,
                           null, ex);
       }

       initOutputStreams();

       //loop through all identified jobs; load and process
       for (Integer jobId : simJobs) {
    	   processSimJobForJobId(jobId);
       }   //job loop

   }

    /**
     * Fetch all unprocessed SimJobs from the database and process them using
     * the local ATN model.
     */
    public void processUnprocessedJobs() throws SQLException {
        List<Integer> jobIds = SimJobDAO.getUnprocessedJobIds(true, 0, 0, "");
        SimJob job;

        for (int jobId : jobIds) {

            job = null;
            try {
                job = SimJobDAO.loadJobNoHistory(jobId, false);
            } catch (SQLException ex) {
                Logger.getLogger(ATNEngine.class.getName()).
                    log(Level.SEVERE, null, ex);
            }

            if (job == null) {
                continue;
            }

            try {
                processSimJob(job);
            } catch (SimulationException ex) {
                Logger.getLogger(ATNEngine.class.getName()).
                    log(Level.SEVERE, null, ex);
            }
        }
    }

	public void processSimJobForJobId(Integer jobId){
	    SimJob job = null;
        System.out.printf("Processing job ID %d\n", jobId);
        try {
            job = SimJobDAO.loadCompletedJob(jobId);

        } catch (SQLException ex) {
            Logger.getLogger(ATNEngine.class
                    .getName()).
                    log(Level.SEVERE, null, ex);
        }
        if (job == null) {
            return;
        }

        //init ecosystem data sets
        EcosystemTimesteps ecosysTimesteps = new EcosystemTimesteps();
        Map<Integer, NodeRelationships> ecosysRelationships = new HashMap<>();

        //extract timestep data from CSV
        Functions.extractCSVDataRelns(job.getCsv(), ecosysTimesteps, ecosysRelationships);
        if (ecosysTimesteps.getTimestepMap().isEmpty()) {
            return;
        }

        long start = System.nanoTime();

        //generate data for current job
        genSpeciesDataset(job, ecosysTimesteps, ecosysRelationships);

        System.out.printf("\nTime... %d seconds\n\n", (System.nanoTime() - start)
                / (long) Math.pow(10, 9));		
	}
   private void initOutputStreams() {
       System.out.println("Ecosystem output will be written to:");
       System.out.println("Network output will be written to:");
       psATN = Functions.getPrintStream("ATN", userInput.destDir);
   }
 	
	public void processSimJob(SimJob job) throws SQLException, SimulationException {
       //init ecosystem data sets
       EcosystemTimesteps ecosysTimesteps = new EcosystemTimesteps();
       Map<Integer, NodeRelationships> ecosysRelationships = new HashMap<>();
       NodeTimesteps nodeTimesteps;
       //extract timestep data from CSV
//       Functions.extractCSVDataRelns(job.getCsv(), ecosysTimesteps, ecosysRelationships);
//       if (ecosysTimesteps.getTimestepMap().isEmpty()) {
//           continue;
//       }
       initOutputStreams();
       int[] nodeListArray = job.getSpeciesNodeList();
       List<SpeciesZoneType> speciesZoneList = job.getSpeciesZoneList();
       
       int timesteps = job.getTimesteps();
       for(int i = 0; i < nodeListArray.length; i++){
	       	int nodeId = nodeListArray[i];
	       	nodeTimesteps = new NodeTimesteps(nodeId, timesteps);
	       	nodeTimesteps.setBiomass(0, speciesZoneList.get(i).getCurrentBiomass());
	       	for (int j = 1; j < timesteps; j++) {
	       		nodeTimesteps.setBiomass(j, 0);
	       	}
	       	ecosysTimesteps.putNodeTimesteps(nodeId, nodeTimesteps);
       }
       ConsumeMap consumeMap = new ConsumeMap(job.getSpeciesNodeList(),
               Constants.ECOSYSTEM_TYPE);
       PathTable pathTable = new PathTable(consumeMap, 
               job.getSpeciesNodeList(), !PathTable.PP_ONLY);
       Log.consoleln("consumeMap " + consumeMap.toString());
       Log.consoleln("pathTable " + pathTable.toString());
       status = Constants.STATUS_SUCCESS;
       
       createEcoSysRelationships(ecosysTimesteps, ecosysRelationships, pathTable.toString());
       
       long start = System.nanoTime();

       //generate data for current job
       genSpeciesDataset(job, ecosysTimesteps, ecosysRelationships);

       System.out.printf("\nTime... %d seconds\n\n", (System.nanoTime() - start)
               / (long) Math.pow(10, 9));
	}
	
   public void createEcoSysRelationships(
		   EcosystemTimesteps ecosysTimesteps,
           Map<Integer, NodeRelationships> ecosysRelationships,
           String csv){
	    //extract relationships
	        int nodeId, timesteps;
	        String spNameNode;
	        NodeTimesteps nodeTimesteps;

	        List<List<String>> dataSet = CSVParser.convertCSVtoArrayList(csv);  

	        //loop through dataset
	        //1 chart: 0: relationship/distance
	        int chart = 0, nodes = 0, relnOffset = 0, distOffset = 0, pathCntOffset = 0;
	        List<Integer> sortedNodeList = null;
	        boolean empty = false;
	        boolean newChart = true;
	        for (List<String> csvLine : dataSet) {
	            //end chart when first blank line is reached
	            if (csvLine.get(0).isEmpty()) {
	                //if empty already flagged, keep looping
	                if (empty == true) {
	                    continue;
	                }
	                //if this is first empty, increment chart#
	                empty = true;
	                newChart = true;
	                chart++;
	                if (chart > 2) {
	                    break;
	                }
	                continue;
	            }
	            empty = false;

	            switch (chart) {
	                case 1:  //relationship/distance chart
	                    //bypass first - header - line
	                    if (newChart) {
	                        sortedNodeList = new ArrayList<>(ecosysTimesteps.getNodeList());
	                        Collections.sort(sortedNodeList);
	                        nodes = sortedNodeList.size();
	                        relnOffset = 2;  //offset in csvLine to 1st reln
	                        distOffset = relnOffset + nodes;  //offset in csvLine to distance info
	                        pathCntOffset = distOffset + nodes; //offset in csvLine to pathCnt info
	                        newChart = false;
	                        break;
	                    }
	                    int nodeA = Integer.valueOf(csvLine.get(0));
	                    NodeRelationships nodeRelns = new NodeRelationships(nodeA);
	                    for (int i = 0; i < nodes; i++) {
	                        int nodeB = sortedNodeList.get(i);
	                        String relnStr = csvLine.get(relnOffset + i);
	                        int dist = Integer.valueOf(csvLine.get(distOffset + i));
	                        int pathCnt = Integer.valueOf(csvLine.get(pathCntOffset + i));

	                        nodeRelns.addRelationship(nodeB, relnStr, dist, pathCnt);
	                    }
	                    ecosysRelationships.put(nodeA, nodeRelns);
	                    break;
	                default:
	                    break;
	            }
	        }
   }
   
   public static void main(String args[]) throws FileNotFoundException, SQLException, SimulationException {
	   LOAD_SIM_TEST_PARAMS = true;
       //get output directory
       JFrame parent = null;
       userInput = new UserInput(parent);
       userInput.destDir = System.getProperty("user.dir");
       userInput.setVisible(true);
       if (userInput.destDir.isEmpty()) {
           System.out.println("Destination directory not specified or user "
                   + "selected cancel.  Aborting run.");
           System.exit(0);
       }

       ATNEngine atn = new ATNEngine();

//       if (equationSet == 0) {
//           //process simulation jobs
//           atn.processJobs();
//       } else {
//           //test ODE w/o ATN model
//           atn.genODETestDataset();
//       }

       if (args.length > 0 && args[0].equals("unprocessed")) {
           System.out.println("Processing unprocessed jobs");
           atn.processUnprocessedJobs();
       } else {
           SimJob job = new SimJob();
           job.setJob_Descript("atn1");
           job.setNode_Config("2,[5],2000,1.000,0,0,[70],2494,13.000,1,X=0.155,0");	//Info comes from client
           job.setManip_Timestamp((new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")).format(new Date()));
           job.setTimesteps(401);
           atn.processSimJob(job);
       }
       System.out.println("Processing complete.");

   }
}
