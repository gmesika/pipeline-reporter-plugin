package io.jenkins.plugins.pipelineReporter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.servlet.ServletException;

import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.cps.actions.ArgumentsActionImpl;
import org.jenkinsci.plugins.workflow.cps.nodes.StepEndNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.graph.AtomNode;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.FlowStartNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.actions.LogActionImpl;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.Environment;
import hudson.model.FreeStyleBuild;
import hudson.model.ParametersAction;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.AffectedFile;
import hudson.scm.ChangeLogSet.Entry;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;

public class PipelineReporterBuilder extends Builder implements SimpleBuildStep {

	private final String pipelineName;
	private final String buildNumber;
	private final String HtmlTemplateFileName;

	private String resolvedPipelineName;
	private int resolvedBuildNumber;
	private String resolvedHtmlTemplateFileName;
	
	private String rootUrl = "";

	@DataBoundConstructor
	public PipelineReporterBuilder(String pipelineName, String buildNumber, String HtmlTemplateFileName) {
		this.pipelineName = pipelineName;
		this.buildNumber = buildNumber;
		this.HtmlTemplateFileName = HtmlTemplateFileName;
	}

	public String getPipelineName() {
		return pipelineName;
	}

	public String getBuildNumber() {
		return buildNumber;
	}

	private String printIndent (int indent)
	{
		String indentation = "";
		for (int counter = 0; counter < indent; counter++)
		{
			indentation = indentation + "\t";
		}
		return indentation;
	}

	private void calculateDurations(SortedMap<Integer, PipelineReportNode>  pipelineNodeReports, TaskListener listener)
	{

		listener.getLogger().println("calculateDurations::calculating duration for each node in map: " + pipelineNodeReports.toString());

		boolean isBlockStartNode = false;
		boolean isBlockEndNode = false;
		boolean isAtomNode = false;

		for (PipelineReportNode pipelineReportNode : pipelineNodeReports.values())
		{
			isBlockStartNode = (pipelineReportNode.getFlowNode() instanceof BlockStartNode); 
			isBlockEndNode = (pipelineReportNode.getFlowNode() instanceof BlockEndNode);
			isAtomNode =  (pipelineReportNode.getFlowNode() instanceof AtomNode);

			FlowNode flowNode = pipelineReportNode.getFlowNode();
			if (isBlockEndNode)
			{				
				String startNodeId = ((BlockEndNode<?>)flowNode).getStartNode().getId();
				long startNodeStartTime = TimingAction.getStartTime(pipelineNodeReports.get(Integer.valueOf(startNodeId)).getFlowNode()); 
				long endNodeStartTime = TimingAction.getStartTime(flowNode);    		
				pipelineReportNode.setExecutionDuration(endNodeStartTime - startNodeStartTime);

				if (flowNode instanceof FlowEndNode) {
					pipelineReportNode.setExecutionDuration(-1);
				}
			}

			if (isAtomNode)
			{
				long atomNodeStartTime = TimingAction.getStartTime(pipelineReportNode.getFlowNode());
				if (pipelineReportNode.getStartFlowNode().getEndFlowNode() != null)
				{
					long endNodeStartTime = TimingAction.getStartTime(pipelineReportNode.getStartFlowNode().getEndFlowNode().getFlowNode());
					pipelineReportNode.setExecutionDuration(endNodeStartTime - atomNodeStartTime);					
				}
				else
				{
					pipelineReportNode.setExecutionDuration(-1);
				}

				if (pipelineReportNode.getPipelineReportNodes() != null)
				{
					calculateDurations(pipelineReportNode.getPipelineReportNodes(), listener);
				}
			}
		}
		listener.getLogger().println("calculateDurations::calculating done in map: " + pipelineNodeReports.toString());
	}

	public void getChildrens(int indent, SortedMap<Integer, PipelineReportNode>  pipelineNodeReports, FlowNode flowNode, TaskListener listener)
	{
		String id = flowNode.getId();

		PipelineReportNode pipelineReportNode = pipelineNodeReports.get(Integer.valueOf(flowNode.getId()));
		pipelineReportNode.setIndent(indent);

		ErrorAction errorAction = flowNode.getAction(ErrorAction.class);
		if (errorAction != null)
		{    		
			pipelineReportNode.setStatus("Failed");
			pipelineReportNode.setStatusMessage(errorAction.getError().getMessage());
		}
		else
		{
			pipelineReportNode.setStatus("Success");
		}

		int indentAdd = indent;
		if (! (flowNode instanceof StepEndNode || flowNode instanceof FlowEndNode))
		{
			//indentAdd = indentAdd + 1;
		}
		else
		{
			//String startNodeId = ((BlockEndNode<?>)flowNode).getStartNode().getId();
			//long startNodeStartTime = TimingAction.getStartTime(pipelineNodeReports.get(Integer.valueOf(startNodeId)).getFlowNode()); 
			//long endNodeStartTime = TimingAction.getStartTime(flowNode);    		
			//pipelineReportNode.setExecutionDuration(endNodeStartTime - startNodeStartTime, pipelineNodeReports);
		}

		int indentRed = indent;
		if (! (flowNode instanceof StepStartNode || flowNode instanceof FlowStartNode))
		{
			//indentRed = indentRed - 1;
		}

		if (flowNode instanceof AtomNode)
		{						
			try {
				Class<? extends Object> atomNodeDescriptorClass = null;
				atomNodeDescriptorClass = org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode.class.getMethod("getDescriptor").invoke(flowNode).getClass();
				if (atomNodeDescriptorClass.toString().equals("class org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerStep$DescriptorImpl"))
				{
					ArgumentsActionImpl argumentsActionImpl = (ArgumentsActionImpl) flowNode.getActions(org.jenkinsci.plugins.workflow.cps.actions.ArgumentsActionImpl.class).get(0);
					if (argumentsActionImpl != null)
					{
						String triggeredJobName = (String)flowNode.getActions(org.jenkinsci.plugins.workflow.cps.actions.ArgumentsActionImpl.class).get(0).getArgumentValue("job");

						LogActionImpl logActionImpl = flowNode.getActions(LogActionImpl.class).get(0);	    				

						if (logActionImpl != null)
						{
							java.io.ByteArrayOutputStream bs = new java.io.ByteArrayOutputStream();
							logActionImpl.getLogText().writeLogTo(0, bs);
							String logText = bs.toString();
							pipelineReportNode.setStatusMessage(logText);

							String regex = triggeredJobName + " #(\\d*)";

							java.util.regex.Pattern MY_PATTERN = java.util.regex.Pattern.compile(regex);
							java.util.regex.Matcher m = MY_PATTERN.matcher(logText);
							while (m.find()) {
								String s = m.group(1);	    				    
								SortedMap atomNodePipeline = getPipelineMap(indent+3, triggeredJobName, Integer.valueOf(s), listener);
								pipelineReportNode.setPipelineReportNodes(atomNodePipeline);
							}		    						    				
						}	    					    					
					}
				} 
			}
			catch (Exception e)
			{
				e.printStackTrace();	
				listener.getLogger().println("getChildrens::Exception: " + e.toString());
			}

		}

		for (PipelineReportNode reportNode : pipelineNodeReports.values())
		{
			FlowNode node = reportNode.getFlowNode();
			List<FlowNode> parents = node.getParents();
			if (parents.contains(flowNode))
			{
				if (node instanceof StepEndNode || node instanceof FlowEndNode)
				{
					getChildrens(indentRed, pipelineNodeReports, node, listener);
				}
				else
				{
					getChildrens(indentAdd, pipelineNodeReports, node, listener);
				}
			}
		}
	}

	private void populateStartAndEnd(SortedMap<Integer, PipelineReportNode> pipelineNodeReports, TaskListener listener)
	{
		listener.getLogger().println("populateStartAndEnd::populating start and end nodes for each node in map: " + pipelineNodeReports.toString());

		boolean isBlockStartNode = false;
		boolean isBlockEndNode = false;
		boolean isAtomNode = false;

		SortedMap<Integer, PipelineReportNode> pipelineNodeReportsReversed = new TreeMap(Collections.reverseOrder());
		pipelineNodeReportsReversed.putAll(pipelineNodeReports);

		for (PipelineReportNode pipelineReportNode : pipelineNodeReportsReversed.values())
		{
			isBlockStartNode = (pipelineReportNode.getFlowNode() instanceof BlockStartNode); 
			isBlockEndNode = (pipelineReportNode.getFlowNode() instanceof BlockEndNode);
			isAtomNode =  (pipelineReportNode.getFlowNode() instanceof AtomNode);

			if (isBlockEndNode)
			{
				String startBlockId = ((BlockEndNode)pipelineReportNode.getFlowNode()).getStartNode().getId();
				PipelineReportNode startPipelineReportNode = pipelineNodeReports.get(Integer.valueOf(startBlockId));

				pipelineReportNode.setStartFlowNode(startPipelineReportNode);
				startPipelineReportNode.setEndFlowNode(pipelineReportNode);
			}

			if (isAtomNode)
			{
				String startNodeId = pipelineReportNode.getFlowNode().getParents().get(0).getId();
				pipelineReportNode.setStartFlowNode(pipelineNodeReports.get(Integer.valueOf(startNodeId)));
				pipelineReportNode.setEndFlowNode(pipelineReportNode.getStartFlowNode().getEndFlowNode());

				if (pipelineReportNode.getPipelineReportNodes() != null)
				{
					populateStartAndEnd(pipelineReportNode.getPipelineReportNodes(), listener);
				}
			}
		}

		listener.getLogger().println("populateStartAndEnd::completed in map: " + pipelineNodeReports.toString());
	}

	private SortedMap<Integer, PipelineReportNode> getPipelineMap(int indent, String pipelineName, int buildNumber, TaskListener listener)
	{
		Jenkins jenkins = Jenkins.getInstance();
		if (jenkins.getRootUrl() != null)
		{
			rootUrl = jenkins.getRootUrl();			
		}
		else
		{
			rootUrl = "http://localhost:8080/jenkins/";
		}
		TopLevelItem tli = jenkins.getItem(pipelineName);

		if (! (tli instanceof WorkflowJob))
		{
			return null;
		}

		WorkflowRun wfr = ((org.jenkinsci.plugins.workflow.job.WorkflowJob)tli).getBuildByNumber(buildNumber);
		listener.getLogger().println("WorkFlowRun: " + wfr.toString());

		java.util.SortedMap<Integer, PipelineReportNode> pipelineNodeReports = new TreeMap<Integer, PipelineReportNode>();   	
		java.util.List<FlowNode> listOfNodes = ((java.util.List<FlowNode>)((WorkflowRun)wfr).getExecution().getCurrentHeads());

		FlowStartNode startNode = null;

		while ( listOfNodes != null && listOfNodes.size() > 0 && listOfNodes.get(0) != null)
		{		
			org.jenkinsci.plugins.workflow.graph.FlowNode flowNode = listOfNodes.get(0);    		
			PipelineReportNode pipelineReportNode = new PipelineReportNode(flowNode.getId(), flowNode);    				
			pipelineNodeReports.put(Integer.valueOf(flowNode.getId()), pipelineReportNode);

			listOfNodes = flowNode.getParents();

			if (flowNode instanceof FlowStartNode)
			{
				listener.getLogger().println("startNode : " + flowNode.toString());
				startNode = (org.jenkinsci.plugins.workflow.graph.FlowStartNode)flowNode;
				pipelineReportNode.setWfr(wfr);
			}			
		}

		getChildrens(indent, pipelineNodeReports, startNode, listener);    	    	

		listener.getLogger().println("getPipelineMap::iteration over all nodes in pipeline completed");
		return pipelineNodeReports;
	}

	public static float round(float d, int decimalPlace) {
		BigDecimal bd = new BigDecimal(Float.toString(d));
		bd = bd.setScale(decimalPlace, BigDecimal.ROUND_HALF_DOWN);
		return bd.floatValue();
	}

	public String formatToHtml (SortedMap<Integer, PipelineReportNode> pipelineMap, TaskListener listener)
	{       	
		listener.getLogger().println("formatToHtml::creating HTML table field for map: " + pipelineMap.toString());
		String htmlOutput = "";

		for (PipelineReportNode pipelineNodeReport : pipelineMap.values())
		{

			float duration = pipelineNodeReport.getExecutionDuration();
			String durationString = "";

			int oneMilliSec = 1;
			int oneSec = oneMilliSec * 1000;
			int oneMin = oneSec * 60;
			int oneHour = oneMin * 60;

			if (duration < 0)
			{
				durationString = "";
			}

			if (duration >= 0 && duration < oneSec)
			{
				duration = round(duration, 1);
				durationString  = duration + " ms ";
			}
			if (duration >= oneSec && duration < oneMin)
			{
				duration = round(duration / oneSec, 1);
				durationString  = duration + " sec ";
			}
			if (duration >= oneMin && duration < oneHour)
			{
				int min = (int) Math.floor(duration / oneMin);
				durationString = String.valueOf(min) + " min ";

				int sec = (int) Math.floor((duration - (oneMin * min)) / oneSec);
				durationString = durationString + String.valueOf(sec) + " sec ";					
			}
			if (duration >= oneHour)
			{
				int hour = (int) Math.floor(duration / oneHour);
				durationString = durationString + String.valueOf(hour) + " h ";

				int min = (int) Math.floor((duration - (oneHour * hour)) / oneMin);
				durationString = durationString + String.valueOf(min) + " min ";

				int sec = (int) Math.floor((duration - (oneHour * hour) - (oneMin * min)) / oneSec);
				durationString = durationString + String.valueOf(sec) + " sec ";				
			}
			if (! (pipelineNodeReport.getFlowNode() instanceof StepEndNode))
			{
				htmlOutput = htmlOutput + "<tr>";
				String icon = "";
				if (pipelineNodeReport.getIndent() > 1)
				{
					char iconChar; 
					if (pipelineNodeReport.getFlowNode() instanceof FlowEndNode)
					{
						iconChar = ExtendedAscii.getAscii(191);
					}
					else 
					{
						iconChar = ExtendedAscii.getAscii(194);
					}

					StringBuilder space = new StringBuilder();
					for (int counter = 0; counter < pipelineNodeReport.getIndent() * 3; counter++)
					{
						space.append(ExtendedAscii.getAscii(254));
					}
					icon = new StringBuilder().append(space).append(iconChar).append(" ").toString();
				}
				htmlOutput = htmlOutput + "<td align=\"center\">" + pipelineNodeReport.getId() + "</td>";

				String step_result_img = getIconLink(pipelineNodeReport.getStatus());

				htmlOutput = htmlOutput + "<td align=\"center\"><img src=\"" + rootUrl + step_result_img + "\" /></td>";
				htmlOutput = htmlOutput + "<td  align=\"left\">" + icon + pipelineNodeReport.getFlowNode().getDisplayName() + "</td>";
				htmlOutput = htmlOutput + "<td>" + durationString + "</td>";

				htmlOutput = htmlOutput + "<td>" + pipelineNodeReport.getStatusMessage() + "</td>";

				listener.getLogger().println(printIndent(pipelineNodeReport.getIndent()) + "id: " + pipelineNodeReport.getId() + " description: " + pipelineNodeReport.getFlowNode().getDisplayName() + " duration: " + duration + " status: " + pipelineNodeReport.getStatus());
				htmlOutput = htmlOutput + "</tr>";

			}

			if (pipelineNodeReport.getPipelineReportNodes() != null)
			{
				htmlOutput = htmlOutput + formatToHtml(pipelineNodeReport.getPipelineReportNodes(), listener);
			}
		}

		listener.getLogger().println("formatToHtml::Done creating HTML fields for map: " + pipelineMap.toString());
		return htmlOutput;
	}

	private void putEnvVar(String key, String value, Run run, TaskListener listener, Launcher launcher) throws IOException {

		listener.getLogger().println("putEnvVar::Injecting HTML into Env var starting...");
	
		StringParameterValue p = new StringParameterValue(key, value);

        ParametersAction a = run.getAction(ParametersActionCust.class);
        if (a!=null) {
            run.replaceAction(a.createUpdated(Collections.singleton(p)));
        } else {
            run.addAction(new ParametersActionCust(p, key));
        }

		listener.getLogger().println("putEnvVar::Injecting HTML into Env VAR completed.");
	}

	private String getFile(String fileName, TaskListener listener) {

		StringBuilder result = new StringBuilder("");

		//Get file from resources folder
		ClassLoader classLoader = getClass().getClassLoader();

		if (classLoader.getResource(fileName).getFile() != null)
		{
			File file = new File(classLoader.getResource(fileName).getFile());

			try (Scanner scanner = new Scanner(file)) {

				while (scanner.hasNextLine()) {
					String line = scanner.nextLine();
					result.append(line).append("\n");
				}

				scanner.close();

			} catch (IOException e) {
				e.printStackTrace();
				listener.getLogger().println("getFile::" + e.toString());
			}
		}
		else
		{
			BufferedReader br = new BufferedReader(new InputStreamReader(classLoader.getResourceAsStream(fileName)));

			String line;
			try {
				while ((line = br.readLine()) != null)
				{
					result.append(line).append("\n");
				}
			} catch (IOException e) {
				listener.getLogger().println("getFile::" + e.toString());
				e.printStackTrace();
			}

		}

		return result.toString();

	}

	private String generateHTMLReport(String htmlOutput, WorkflowRun wfr,String pipeLinestatus, TaskListener listener)
	{
		listener.getLogger().println("generateHTMLReport::Generating HTML");

		String templateFile = getFile(HtmlTemplateFileName, listener);


		String changeHTML = "";
		String changesHTML = "";
		List<ChangeLogSet<? extends Entry>> changes = wfr.getChangeSets();
		if (changes != null && changes.size() > 0)
		{
			for (ChangeLogSet<? extends Entry> change : changes)
			{
				for (Object entry : change.getItems())
				{
					changeHTML = changeHTML + "<h2>" + ((ChangeLogSet.Entry)entry).getMsgAnnotated() + "</h2>";
					changeHTML = changeHTML + "<p>by <em>" + ((ChangeLogSet.Entry)entry).getAuthor() + "</em></p>";
					boolean includeFiles = false;

					if (includeFiles)
					{
						changeHTML = changeHTML + "<table>";
						for (AffectedFile file : ((ChangeLogSet.Entry)entry).getAffectedFiles())
						{
							changeHTML = changeHTML + "<tr>";
							changeHTML = changeHTML + "<td>" + file.getPath() + "</td>";						
							changeHTML = changeHTML + "</tr>";
						}
						changeHTML = changeHTML + "</table>";
					}

				}
			}
		}
		else
		{
			changeHTML = changeHTML + "<p>No Changes</p>";
		}
		templateFile = templateFile.replaceAll("\\$CHANGES", changesHTML);

		templateFile = templateFile.replaceAll("\\$PIPELINE_HTML_TABLE", htmlOutput);
		templateFile = templateFile.replaceAll("\\$result_img", getIconLink(pipeLinestatus));
		templateFile = templateFile.replaceAll("\\$url", wfr.getUrl());
		templateFile = templateFile.replaceAll("\\$durationString", wfr.getDurationString());
		templateFile = templateFile.replaceAll("\\$timestampString", wfr.getTimestampString() + " ago");
		templateFile = templateFile.replaceAll("\\$buildNumber", String.valueOf(wfr.getNumber()));

		String causes = "";
		for (Cause cause : wfr.getCauses())
		{
			causes = causes + cause.getShortDescription() + "\n";
		}
		templateFile = templateFile.replaceAll("\\$CAUSES", causes);

		templateFile = templateFile.replaceAll("\\$rooturl", rootUrl);
		templateFile = templateFile.replaceAll("\\$name", wfr.getParent().getName());
		templateFile = templateFile.replaceAll("\\$result", pipeLinestatus);

		listener.getLogger().println("generateHTMLReport::Done generating HTML");
		return templateFile;
	}

	private String getIconLink(String status)
	{
		String link = "";
		if (status.equalsIgnoreCase("success"))
		{
			link = "static/e59dfe28/images/32x32/blue.gif";
		}	else if (status.equalsIgnoreCase("failed")) {
			link = "static/e59dfe28/images/32x32/red.gif";
		} else {
			link = "static/e59dfe28/images/32x32/yellow.gif";
		}	
		return link;
	}

	private void verifyBuildExistsInPipeline(String pipelineName, int buildNumber)
	{
		Jenkins jenkins = Jenkins.getInstance();
		TopLevelItem tli = jenkins.getItem(pipelineName);

		if (! (tli instanceof WorkflowJob))
		{
			throw new RuntimeException(pipelineName + " is not an instance of: " + WorkflowJob.class.getName().toString());
		}

		WorkflowRun wfr = ((org.jenkinsci.plugins.workflow.job.WorkflowJob)tli).getBuildByNumber(buildNumber);
		if (wfr == null)
		{
			throw new RuntimeException("Build #" + buildNumber + " dosent exists...");
		}
	}

	private int getLastBuild(String pipelineName)
	{
		Jenkins jenkins = Jenkins.getInstance();
		TopLevelItem tli = jenkins.getItem(pipelineName);

		return Integer.valueOf(((WorkflowJob)tli).getLastBuild().getId());
	}

	private void verifyItsPipeline(String pipelineName)
	{
		Jenkins jenkins = Jenkins.getInstance();
		TopLevelItem tli = jenkins.getItem(pipelineName);

		if (! (tli instanceof WorkflowJob))
		{
			throw new RuntimeException("Pipeline: " + pipelineName + " is not an instance of: " + WorkflowJob.class.getName().toString() + " , but of: " +  tli.getClass());
		}
	}

	private String replaceEnvVarValue(Run<?, ?> run, TaskListener listener, String paramValue, String regex) throws IOException, InterruptedException
	{
		java.util.regex.Pattern MY_PATTERN = java.util.regex.Pattern.compile(regex);
		java.util.regex.Matcher m = MY_PATTERN.matcher(paramValue);
		while (m.find()) {
			String s = m.group(1);	
			try {
				return run.getEnvironment(listener).get(s);
			} catch (IOException e) {
				e.printStackTrace();
				throw e;
			} catch (InterruptedException e) {
				e.printStackTrace();
				throw e;
			}
		}		 
		
		return paramValue;
	}
	
	private String replaceEnvVar(Run<?, ?> run, TaskListener listener, String paramValue) throws IOException, InterruptedException
	{
		String resolvedPipelineName = replaceEnvVarValue(run, listener, paramValue, "\\$\\{(.*)\\}");
		if (resolvedPipelineName.equals(paramValue))
		{
			resolvedPipelineName = replaceEnvVarValue(run, listener, paramValue, "\\$(.*)");
		}
		return resolvedPipelineName;
	}
	
	private void resolve(Run<?, ?> run, TaskListener listener) throws IOException, InterruptedException
	{
		resolvedPipelineName = replaceEnvVar (run, listener, pipelineName);
		resolvedBuildNumber = Integer.valueOf(replaceEnvVar (run, listener, String.valueOf(buildNumber)));
		resolvedHtmlTemplateFileName = replaceEnvVar (run, listener, HtmlTemplateFileName);
	}
	
	@Override
	public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {

		listener.getLogger().println("Pipeline Repoter Started...");

		String _pipelineName = null;
		int _buildNumber = 0;
		String _HtmlTemplateFileName = null;

		resolve(run,listener);
		
		/**
		 * plugin executed in the same pipeline, no need to define the pipeline  and number.
		 * code will fetch it from actual execution object (run) 
		 */
		if ((resolvedPipelineName == null || resolvedPipelineName.contentEquals("")) && resolvedBuildNumber == 0)
		{
			_pipelineName = run.getParent().getName();
			verifyItsPipeline(_pipelineName);
			_buildNumber = run.getNumber();
			// no need to verify the build, if it is a pipeline it's in the same build...
		}

		/**
		 * plugin executed with a specific build number, maybe user
		 * needs to to run on same build for different pipelines.
		 * 
		 * usually this is needed for testing report look and feel...
		 */
		if (resolvedPipelineName == null || resolvedPipelineName.contentEquals("") && resolvedBuildNumber != 0)
		{
			_pipelineName = run.getParent().getName();
			verifyItsPipeline(_pipelineName);
			_buildNumber = resolvedBuildNumber;
			verifyBuildExistsInPipeline(_pipelineName, _buildNumber);
		}

		/**
		 * plugin executed with a specific pipeline job name and
		 * without a build number - expected to get latest build of specific pipeline
		 * 
		 *  this is most common when executing the plugin outside the pipeline
		 */		
		if ((resolvedPipelineName != null &&  ! resolvedPipelineName.contentEquals("")) && resolvedBuildNumber == 0)
		{
			_pipelineName = resolvedPipelineName;
			verifyItsPipeline(_pipelineName);
			_buildNumber = getLastBuild(_pipelineName);
			// no need to verify build number as it was just fetched...
		}

		/**
		 * plugin executes with a specific pipeline job name and build number.
		 * 
		 * most common when a pipeline will be executed in a different job that and
		 * pass the parameters as inputs, enabling to specify different build number then last one
		 */
		if ((resolvedPipelineName != null &&  ! resolvedPipelineName.contentEquals("")) && resolvedBuildNumber != 0)
		{
			_pipelineName = resolvedPipelineName;
			verifyItsPipeline(_pipelineName);
			_buildNumber = resolvedBuildNumber;
			verifyBuildExistsInPipeline(_pipelineName, _buildNumber);
		}

		if (resolvedHtmlTemplateFileName == null || resolvedHtmlTemplateFileName.contentEquals(""))
		{
			_HtmlTemplateFileName = "jenkins-generic-matrix-email-html.template";
		}
		else
		{
			_HtmlTemplateFileName = resolvedHtmlTemplateFileName;
		}
		listener.getLogger().println("Pipeline Name: " + _pipelineName);
		listener.getLogger().println("Pipeline Build Number: " + _buildNumber);
		listener.getLogger().println("Html Template File Name: " + _HtmlTemplateFileName);

		SortedMap<Integer, PipelineReportNode> pipelineMap = getPipelineMap(1, _pipelineName, _buildNumber, listener);
		populateStartAndEnd(pipelineMap, listener);
		calculateDurations(pipelineMap, listener);
		String htmlOutput = formatToHtml(pipelineMap, listener);
		htmlOutput = generateHTMLReport(htmlOutput, pipelineMap.get(Integer.valueOf(2)).getWfr(), pipelineMap.get(Integer.valueOf(2)).getStatus(), listener);
		putEnvVar("HTML_OUTPUT", htmlOutput, run, listener, launcher);

		listener.getLogger().println ("\n\n" + htmlOutput + "\n\n");
		listener.getLogger().println("Pipeline HTML Report Generated !");
	}

	@Symbol("report")
	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

		public FormValidation doCheckName(@QueryParameter String value, @QueryParameter int buildNumber)
				throws IOException, ServletException {

			return FormValidation.ok();
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			return true;
		}

		@Override
		public String getDisplayName() {
			return Messages.PipelineReporterBuilder_DescriptorImpl_DisplayName();
		}

	}

}
