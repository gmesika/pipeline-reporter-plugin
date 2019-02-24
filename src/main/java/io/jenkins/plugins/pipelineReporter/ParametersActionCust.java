package io.jenkins.plugins.pipelineReporter;

import hudson.EnvVars;
import hudson.model.EnvironmentContributingAction;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.Queue.QueueAction;
import hudson.model.labels.LabelAssignmentAction;
import jenkins.model.RunAction2;

public class ParametersActionCust extends hudson.model.ParametersAction
implements RunAction2, Iterable<ParameterValue>, QueueAction, EnvironmentContributingAction, LabelAssignmentAction{

	String  parameterName;
	
    public ParametersActionCust(StringParameterValue p, String paramName) {
    	super(p);
    	parameterName = paramName;
	}
	
    public void buildEnvironment(Run<?,?> run, EnvVars env)  {
    	if (this.getParameter(parameterName) != null)
    	{
    		this.getParameter(parameterName).buildEnvironment(run, env);
    	}    	
    }
}
