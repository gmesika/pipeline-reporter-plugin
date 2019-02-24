package io.jenkins.plugins.pipelineReporter;

import java.util.SortedMap;

import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.FlowStartNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

public class PipelineReportNode {
	
	public void setWfr(WorkflowRun wfr) {
		this.wfr = wfr;
	}
	PipelineReportNode(String id, FlowNode flowNode)
	{
		this.id = id;
		this.flowNode = flowNode;
		this.statusMessage = "";
	}
	String id;
	public String getId() {
		return id;
	}
	public void setId(String id) {
		this.id = id;
	}
	int indent;
	public int getIndent() {
		return indent;
	}
	public void setIndent(int indent) {
		this.indent = indent;
	}
	long executionDuration;
	public long getExecutionDuration() {
		return executionDuration;
	}
	public void addExecutionDuration(long l)
	{
		this.executionDuration = this.executionDuration + l;
		
	}
	
	FlowNode flowNode;
	public FlowNode getFlowNode() {
		return flowNode;
	}
	public void setFlowNode(FlowNode flowNode) {
		this.flowNode = flowNode;
	}
	String status;
	public String getStatus() {
		return status;
	}
	public void setStatus(String status) {
		this.status = status;
	}
	String statusMessage;
	public void setStatusMessage(String statusMessage) {
		this.statusMessage = statusMessage;
	}
	public String getStatusMessage() {
		return statusMessage;
	}
	
	SortedMap<Integer, PipelineReportNode> pipelineReportNodes;
	public SortedMap<Integer, PipelineReportNode> getPipelineReportNodes() {
		return pipelineReportNodes;
	}
	public void setPipelineReportNodes(SortedMap<Integer, PipelineReportNode> pipelineReportNodes) {
		this.pipelineReportNodes = pipelineReportNodes;
	}
	
	PipelineReportNode startFlowNode;
	
	public PipelineReportNode getStartFlowNode() {
		return startFlowNode;
	}
	public void setStartFlowNode(PipelineReportNode startFlowNode) {
		this.startFlowNode = startFlowNode;
	}
	public PipelineReportNode getEndFlowNode() {
		return endFlowNode;
	}
	public void setEndFlowNode(PipelineReportNode endFlowNode) {
		this.endFlowNode = endFlowNode;
	}
	PipelineReportNode endFlowNode;
	
	public void setExecutionDuration(long executionDuration) {
		this.executionDuration = executionDuration;
		
		if (flowNode instanceof BlockEndNode)
		{
			this.getStartFlowNode().setExecutionDuration(executionDuration);			
		}
	}
	WorkflowRun wfr;
	public WorkflowRun getWfr() {
		return wfr;
	}
	
}
