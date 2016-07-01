/*******************************************************************************/
/* Copyright (C) 2016, International Business Machines Corporation             */
/* All Rights Reserved                                                         */
/*******************************************************************************/

// Class to use with MetricsTupleCounter operator
// Operator requires 3 rstring output attributes
package com.ibm.streamsx.perf.measurements;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import com.ibm.json.java.*; 

import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateException;

import javax.management.remote.JMXServiceURL;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.JMX;
import javax.management.MBeanServerConnection;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

import com.ibm.streams.operator.samples.patterns.ProcessTupleProducer;
import com.ibm.streams.operator.StreamingOutput;
import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.internal.runtime.api.SPLRuntime;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.Attribute;
import com.ibm.streams.operator.Type;
import com.ibm.streams.operator.OperatorContext.ContextCheck;

import com.ibm.streams.management.ObjectNameBuilder;
import com.ibm.streams.management.domain.DomainMXBean;
import com.ibm.streams.management.instance.InstanceMXBean;
import com.ibm.streams.management.job.JobMXBean;
import com.ibm.streams.management.persistence.curator.TreePersistence;
import com.ibm.streams.admin.internal.api.StreamsDomain;
import com.ibm.streams.admin.internal.api.StreamsInstall;

import java.util.Set;
import java.util.Iterator;
import com.ibm.streams.management.job.OperatorMXBean;

import com.ibm.streams.operator.compile.OperatorContextChecker;



public class MetricsTupleCounter extends ProcessTupleProducer {

  // Compile time checks (SPL compiler) for valid parms and schema
  @ContextCheck
  public static void checkOperatorContext(final OperatorContextChecker checker) {
	  final OperatorContext context = checker.getOperatorContext();
	  //List<StreamingOutput<OutputTuple>>  outputs = context.getStreamingOutputs();
	  StreamSchema ss = context.getStreamingOutputs().get(0).getStreamSchema();	

	  boolean attrsOK = true;
	  if (3 != ss.getAttributeCount()) {
		  attrsOK = false;
	  }
	  else {
		  for (int i=0; i < ss.getAttributeCount(); i++) {
			  Attribute nextAttr = ss.getAttribute(i);
			  if ((0 == i) || (2 == i)) {
			    if (nextAttr.getType().getMetaType() != Type.MetaType.FLOAT64) {
				  attrsOK = false;
			    }
			  }
			  else if (1 == i) {
				    if (nextAttr.getType().getMetaType() != Type.MetaType.UINT64) {
					  attrsOK = false;
				    }
				  }			  
		  }
	  }
	  if (false == attrsOK)
        checker.setInvalidContext("MetricsTupleCounter operator must be invoked with output attributes of float64 (duration), uint64 (tuple), and float64 (rate).", null);	 
         
  }

  // Used to indicate if we want to measure flow through
  // an input port or an output port.
  public enum MeasurementType {
	  outputPort,
	  inputPort;
  }

  // Used to represent what metric data 
  // we want to capture
  private static class MeasurementInfo {
	  public MeasurementInfo() {}
	  public long peIndex;
	  public String operatorName;
	  public long portIndex;
  }
  
  // Used to capture metric data
  private static class MeasurementData {
	  public long ts;
	  public long tupleCount;
  }

  
  public void initialize(OperatorContext context)
          throws java.lang.Exception {
	  super.initialize(context);
	  
	  // Set values from operator paramaters:
	  setMeasurementType();
	  setStreamName();
	  setTuplesPerMeasurementInterval();
	  setMetricCollectionInterval();
	  setMeasurementIntervals();
      setJobID();
  }
          

  protected void process() throws Exception {
	  
	if (getOperatorContext().getPE().isStandalone())
      throw new Exception("MetricsTupleCounter operator not supported in standalone mode.");
	   
    String domainName = getOperatorContext().getPE().getDomainId();
    String instanceName = getOperatorContext().getPE().getInstanceId();
    
    BigInteger jobID = getJobID();
    if (-1 == jobID.intValue()) {
      jobID = getOperatorContext().getPE().getJobId();
    }
      
    // Get mbean server connection
    MBeanServerConnection mbsc = getMBeanServerConnection(domainName);
      
    // Set up SSL
    setupSSL(); 
      
    // Get a handle on Domain, Instance, and Job Beans.  Register the job with the intance bean
    //DomainMXBean domainBean = JMX.newMXBeanProxy(mbsc, ObjectNameBuilder.domain(domainName), DomainMXBean.class, true);
    InstanceMXBean instanceBean = JMX.newMXBeanProxy(mbsc,  ObjectNameBuilder.instance(domainName, instanceName), InstanceMXBean.class, true);
    instanceBean.registerJob(jobID);
    JobMXBean jobBean = JMX.newMXBeanProxy(mbsc, ObjectNameBuilder.job(domainName, instanceName, jobID), JobMXBean.class, true);
    
    
    // Get a snapshot of the job to figure out which PE/Operator/Connection metric we will be looking for later on.
    MeasurementInfo mInfo = findOperatorLocation(jobBean);
    
    // Capture metrics data on regular collection intervals until
    // the number of tuples flowing through the operator
    // equals the number specified on the tupleCount parameter.
    long startingTupleCount = -1;
    long startingTime = -1;  
    long intervalCount = 0;
    final StreamingOutput<OutputTuple> out = getOutput(0);
    OutputTuple tuple = out.newTuple();    
    
    while (intervalCount < getMeasurementIntervals()) {
      MeasurementData mData = new MeasurementData();   
      long tupleCount = -1;
      while (tupleCount < getTuplesPerMeasurementInterval()) {
          mData = getMeasurementData(jobBean, mInfo);
          if (-1 == startingTime) {
            if (mData.tupleCount > 0) {
              startingTime = mData.ts;
              startingTupleCount = mData.tupleCount;
            }
          }
          tupleCount = mData.tupleCount - startingTupleCount;
          if (tupleCount < getTuplesPerMeasurementInterval())
            Thread.sleep(getMetricCollectionInterval());          
      }
      double duration = ((double)mData.ts - (double)startingTime) / 1000.0;
      double rate = (double)tupleCount / duration;
      tuple.setDouble(0, duration);
      tuple.setLong(1, tupleCount);
      tuple.setDouble(2, rate);
      out.submit(tuple);
      
      startingTime = mData.ts;
      startingTupleCount = mData.tupleCount;
      ++intervalCount;
    }
    
    
    return;        
  }
  
  
  // Get the connection to the server.
  private MBeanServerConnection getMBeanServerConnection(String domainName) throws Exception {
	  
	// Get the JMX URL from ZK.  Note that these calls are really not intended for external usage like this
    TreePersistence zk = new TreePersistence(domainName, System.getenv("STREAMS_ZKCONNECT"), null, false);
    StreamsInstall streamsInstall = StreamsInstall.getStreamsInstall(System.getenv("STREAMS_INSTALL"));
    StreamsDomain domain = StreamsDomain.getStreamsDomain(domainName, streamsInstall, zk);
    String jmxUrl = domain.getJmxUrl();
      
    // Pull an AAS token from the runtime that we can use to connect to JMX
    final String[] creds = SPLRuntime.get().getActive().getExecutionContext().getDomainCredentials();
    
    HashMap<String, Object> env = new HashMap<String, Object>();
    env.put("jmx.remote.credentials", creds);
    env.put("jmx.remote.protocol.provider.pkgs", "com.ibm.streams.management");
    env.put("jmx.remote.tls.enabled.protocols", domain.getJmxService().getSSLOption().toString());
    
      
    // Connect to JMX   
    final JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(jmxUrl), env);
    MBeanServerConnection mbsc = connector.getMBeanServerConnection();
	return(mbsc);
  }
  
  private void setupSSL() throws Exception {
    // Set up SSL so that we will trust signer certificates as well as verify any default hostname
    TrustManager[] trustAllCerts = new TrustManager[] { 
                new X509TrustManager() {     
                  public java.security.cert.X509Certificate[] getAcceptedIssuers() { 
                      return new X509Certificate[0];                    
                  }                  
                  public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {} 
                  public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) throws CertificateException {}
              } 
          };     
    SSLContext ctx = SSLContext.getInstance("TLSv1");
    ctx.init(null, trustAllCerts, null); 
    SSLSocketFactory ssf = ctx.getSocketFactory(); 
    HttpsURLConnection.setDefaultSSLSocketFactory(ssf);    
    HostnameVerifier hv = new HostnameVerifier() {
        public boolean verify(String hostname, SSLSession session) {
      	  return(true);
        }
    };
    HttpsURLConnection.setDefaultHostnameVerifier(hv);
    return;
  }
  
  private String getStringFromConnection(HttpsURLConnection conn) throws Exception {
    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
	String inputLine;
	String jsonString = "";
	while ((inputLine = in.readLine()) != null) {
	  jsonString = jsonString + inputLine;
	}
	in.close();    
	return(jsonString);  
  }
  
  // Call snapshot() to get job layout so we can find the the PE/Operator/Port that
  // we are going to be looking for later on to examine metrics
  private MeasurementInfo findOperatorLocation(JobMXBean jobBean) throws Exception {

	  URL jobUrl = new URL(jobBean.snapshot(-1, true));
	  HttpsURLConnection conn = (HttpsURLConnection) jobUrl.openConnection();
	  conn.setRequestMethod("GET");      
	  conn.connect();	  
	  String jsonString = getStringFromConnection(conn);
	  
	  JSONObject jobInfo = JSONObject.parse(jsonString); 
	  JSONArray pes = (JSONArray)jobInfo.get("pes");
	  Iterator i1 = pes.iterator();
        while (i1.hasNext()) {
        	JSONObject nextPE = (JSONObject)i1.next();
        	Long peIndex = (Long)nextPE.get("indexWithinJob");
        	JSONArray operators = (JSONArray)nextPE.get("operators");
      	    Iterator i2 = operators.iterator();
            while (i2.hasNext()) {
            	JSONObject nextOperator = (JSONObject)i2.next();
            	Long operatorIndex = (Long)nextOperator.get("indexWithinJob");
            	
            	// If we are looking for output port data
            	if (getMeasurementType() == MeasurementType.outputPort){ 
            	  JSONArray outputPorts = (JSONArray)nextOperator.get("outputPorts");
          	      Iterator i3 = outputPorts.iterator();
                  while (i3.hasNext()) {
                	  JSONObject nextOutputPort = (JSONObject)i3.next();
                	  String outputPortName = (String)nextOutputPort.get("name");
                	  if (outputPortName.equals(getStreamName())) {
                		  MeasurementInfo mInfo = new MeasurementInfo();
                		  mInfo.peIndex = peIndex;
                		  mInfo.operatorName = (String)nextOperator.get("name");
                		  mInfo.portIndex = (Long)nextOutputPort.get("indexWithinOperator");
                		  return(mInfo);
                	  }
                  }
            	}

            	// else if we are looking for input port data
            	else if (getMeasurementType() == MeasurementType.inputPort){ 
            	  JSONArray inputPorts = (JSONArray)nextOperator.get("inputPorts");
          	      Iterator i4 = inputPorts.iterator();
                  while (i4.hasNext()) {
                	JSONObject nextInputPort = (JSONObject)i4.next();
                	String inputPortName = (String)nextInputPort.get("name");
              	    if (inputPortName.equals(getStreamName())) {
                 		  MeasurementInfo mInfo = new MeasurementInfo();
                		  mInfo.peIndex = peIndex;
                		  mInfo.operatorName = (String)nextOperator.get("name");
                		  mInfo.portIndex = (Long)nextInputPort.get("indexWithinOperator");
                		  return(mInfo);              	      
              	    }
                  }  
            	}
                
            }
        }	  

      // should never get to here if user specified a correct value for the streamName parameter
      throw new Exception("Cannot find stream " + getStreamName() + " in this job.");
  }
  
  // Calls snapshotMetrics() to get metrics.  The info to parse out of the metrics output is 
  // determined by the contents of mInfo.  This routine is designed to be called multiple times.
  private MeasurementData getMeasurementData(JobMXBean jobBean, MeasurementInfo mInfo) throws Exception {
	    URL jobUrl = new URL(jobBean.snapshotMetrics());
	    HttpsURLConnection conn = (HttpsURLConnection) jobUrl.openConnection();
	    conn.setRequestMethod("GET");      
	    conn.connect();
	    
	    // Read the metrics info from the URL    
	    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
	    String inputLine;
		String jsonString = getStringFromConnection(conn);
		
	    JSONObject jobInfo = JSONObject.parse(jsonString); 
		JSONArray pes = (JSONArray)jobInfo.get("pes");
		Iterator i1 = pes.iterator();
	    while (i1.hasNext()) {
          JSONObject nextPE = (JSONObject)i1.next();
          Long peIndex = (Long)nextPE.get("indexWithinJob");
          if (peIndex.longValue() == mInfo.peIndex) {
          	JSONArray operators = (JSONArray)nextPE.get("operators");
      	    Iterator i2 = operators.iterator();
            while (i2.hasNext()) {
              JSONObject nextOperator = (JSONObject)i2.next();
              String operatorName = (String)nextOperator.get("name");
              if (operatorName.equals(mInfo.operatorName)) {
            	  
            	// If looking for outputPort data grab nTuplesSubmitted metric
              	if (getMeasurementType() == MeasurementType.outputPort){
                  JSONArray outputPorts = (JSONArray)nextOperator.get("outputPorts");
            	  Iterator i3 = outputPorts.iterator();
                  while (i3.hasNext()) {
                    JSONObject nextOutputPort = (JSONObject)i3.next();
                    Long portIndex = (Long)nextOutputPort.get("indexWithinOperator");
                    if (portIndex.longValue() == mInfo.portIndex) {
                      JSONArray metrics = (JSONArray)nextOutputPort.get("metrics");
                	  Iterator i4 = metrics.iterator();
                      while (i4.hasNext()) {
                        JSONObject nextMetric = (JSONObject)i4.next();
                        String metricName = (String)nextMetric.get("name");
                        if (metricName.equals("nTuplesSubmitted")) {
                            Long tupleCount = (Long)nextMetric.get("value");
                            MeasurementData mData = new MeasurementData();
                            mData.tupleCount = tupleCount;
                            mData.ts = (Long)nextPE.get("lastTimeRetrieved");
                            return(mData);
                        }
                      }
                    }
                  }              		
              	}
              	
              	// else if looking for inputPort data grab nTuplesProcessed metric
              	else if (getMeasurementType() == MeasurementType.inputPort){
                  JSONArray inputPorts = (JSONArray)nextOperator.get("inputPorts");
                  Iterator i3 = inputPorts.iterator();
                  while (i3.hasNext()) {
                    JSONObject nextInputPort = (JSONObject)i3.next();
                    Long portIndex = (Long)nextInputPort.get("indexWithinOperator");
                    if (portIndex.longValue() == mInfo.portIndex) {
                      JSONArray metrics = (JSONArray)nextInputPort.get("metrics");
                      Iterator i4 = metrics.iterator();
                      while (i4.hasNext()) {
                        JSONObject nextMetric = (JSONObject)i4.next();
                        String metricName = (String)nextMetric.get("name");
                        if (metricName.equals("nTuplesProcessed")) {
                          Long tupleCount = (Long)nextMetric.get("value");
                          MeasurementData mData = new MeasurementData();
                          mData.tupleCount = tupleCount;
                          mData.ts = (Long)nextPE.get("lastTimeRetrieved");
                          return(mData);
                        }
                      }
                    }
                  }                    		              	
              	}              	
              }
            }
          }

	    }

	    // Shouldn't ever get here.
	    throw new Exception("Cannot find requested tuple count in this job.");
  }
  
  
  // Methods to retrieve values passed in by parameters
  private MeasurementType  measurementType;  
  public void setMeasurementType() {
    String s = getOperatorContext().getParameterValues("measurementType").get(0);
    if (s.equals("inputPort"))
      this.measurementType = MeasurementType.inputPort;
    else
      this.measurementType = MeasurementType.outputPort;
  }  
  private MeasurementType getMeasurementType() {
    return(this.measurementType);
  }
  
  private String streamName;
  private void setStreamName() {
	  streamName = getOperatorContext().getParameterValues("streamName").get(0);
  }
  private String getStreamName() {return(streamName);}
  
  private long tuplesPerMeasurementInterval;
  private void setTuplesPerMeasurementInterval() {
	  tuplesPerMeasurementInterval = Long.parseLong(getOperatorContext().getParameterValues("tuplesPerMeasurementInterval").get(0));
  }
  private long getTuplesPerMeasurementInterval() { return(tuplesPerMeasurementInterval);}
  
  private long measurementIntervals;
  private void setMeasurementIntervals() {
    java.util.List<java.lang.String> intervalParm = getOperatorContext().getParameterValues("measurementIntervals");
	if (0 == intervalParm.size())
	  measurementIntervals = 1;
	else 
	  measurementIntervals =  Long.parseLong(intervalParm.get(0));
	if (measurementIntervals <= 0)
      measurementIntervals = 1;
  }
  private long getMeasurementIntervals() { return(measurementIntervals);}
  
  private long metricCollectionInterval;
  private void setMetricCollectionInterval() {
	java.util.List<java.lang.String> intervalParm = getOperatorContext().getParameterValues("metricCollectionInterval");
	if (0 == intervalParm.size())
	  metricCollectionInterval = 3 * 1000;
	else 
	  metricCollectionInterval = (long)(Double.parseDouble(intervalParm.get(0)) * 1000);
  }
  private long getMetricCollectionInterval() { return(metricCollectionInterval); }
  
  private BigInteger jobID;
  private void setJobID() {
    java.util.List<java.lang.String> jobParm = getOperatorContext().getParameterValues("jobID");
	if (0 == jobParm.size())
		jobID = BigInteger.valueOf(-1);
	else
		jobID = BigInteger.valueOf(Integer.parseInt(jobParm.get(0)));
    if (jobID.intValue() < -1)
	  jobID = BigInteger.valueOf(-1);
  }
  private BigInteger getJobID() { return(jobID);}
}

