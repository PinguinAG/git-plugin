package hudson.plugins.git;

import java.io.IOException;
import java.io.PrintStream;
import java.text.MessageFormat;
import java.util.List;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.GitSCMExtensionDescriptor;
import hudson.plugins.git.util.BuildData;
import hudson.util.DescribableList;

import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.CloneCommand;
import org.jenkinsci.plugins.gitclient.FetchCommand;
import org.jenkinsci.plugins.gitclient.GitClient;

public class GitPollingManager {
	private static GitPollingManager instance;
	private GitPoller poller;
	
	private class GitPoller implements Runnable {
		private TaskListener listener;
		private GitClient git;
		private AbstractProject<?, ?> project;
		private EnvVars environment;
		private DescribableList<GitSCMExtension, GitSCMExtensionDescriptor> extensions;
		private GitSCM gitSCM;
		private BuildData buildData;

		public GitPoller(GitSCM gitSCM, TaskListener listener, GitClient git, AbstractProject<?, ?> project, BuildData buildData, EnvVars environment, DescribableList<GitSCMExtension, GitSCMExtensionDescriptor> extensions) {
			this.gitSCM = gitSCM;
			this.listener = listener;
			// TODO Auto-generated constructor stub
			this.git = git;
			this.project = project;
			this.buildData = buildData;
			this.environment = environment;
			this.extensions = extensions;
		}

		public void run() {
            try {
                final PrintStream log = listener.getLogger();

                List<RemoteConfig> repos = gitSCM.getParamExpandedRepos(project.getLastBuild());
                if (repos.isEmpty())    return; // defensive check even though this is an invalid configuration

                if (git.hasGitRepo()) {
                    // It's an update
                    if (repos.size() == 1)
                        log.println("Fetching changes from the remote Git repository");
                    else
                        log.println(MessageFormat.format("Fetching changes from {0} remote Git repositories", repos.size()));
                } else {
                    log.println("Cloning the remote Git repository");

                    RemoteConfig rc = repos.get(0);
                    try {
                        CloneCommand cmd = git.clone_().url(rc.getURIs().get(0).toPrivateString()).repositoryName(rc.getName());
//                        for (GitSCMExtension ext : extensions) {
//                            ext.decorateCloneCommand(gitSCM, project.getLastBuild(), git, listener, cmd);
//                        }
                        cmd.execute();
                    } catch (GitException ex) {
                        ex.printStackTrace(listener.error("Error cloning remote repo '%s'", rc.getName()));
                        throw new AbortException();
                    }
                }

                for (RemoteConfig remoteRepository : repos) {
                    fetchFrom(git, listener, remoteRepository);
                }
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
	    /**
	     * Fetch information from a particular remote repository.
	     *
	     * @param git
	     * @param listener
	     * @param remoteRepository
	     * @throws
	     */
	    private void fetchFrom(GitClient git,
	            TaskListener listener,
	            RemoteConfig remoteRepository) throws InterruptedException, IOException {

	        boolean first = true;
	        for (URIish url : remoteRepository.getURIs()) {
	            try {
	                if (first) {
	                    git.setRemoteUrl(remoteRepository.getName(), url.toPrivateASCIIString());
	                    first = false;
	                } else {
	                    git.addRemoteUrl(remoteRepository.getName(), url.toPrivateASCIIString());
	                }

	                FetchCommand fetch = git.fetch_().from(url, remoteRepository.getFetchRefSpecs());
	                for (GitSCMExtension extension : extensions) {
	                    extension.decorateFetchCommand(gitSCM, git, listener, fetch);
	                }
	                fetch.execute();
	            } catch (GitException ex) {
	                throw new GitException("Failed to fetch from "+url.toString(), ex);
	            }
	        }
	    }	
	}
	
	public static synchronized GitPollingManager getInstance() {
		if (instance == null) {
			instance = new GitPollingManager();
		}
		return instance;
	}

	public void hasChanges(GitSCM gitSCM, TaskListener listener, GitClient git, AbstractProject<?, ?> project, BuildData buildData, EnvVars environment, DescribableList<GitSCMExtension, GitSCMExtensionDescriptor> extensions) {
		try {
			synchronized (this) {
				if (poller == null) {
					poller = new GitPoller(gitSCM, listener, git, project, buildData, environment, extensions);
					Thread t = new Thread(poller);
					t.run();
				}
			}
			
			poller.wait();
			
			synchronized (this) {
				poller = null;				
			}
			
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
