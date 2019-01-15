/*
 * The MIT License
 *
 * Copyright 2013 Mirko Friedenhagen.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.jobConfigHistory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.google.common.collect.Lists;

import difflib.Delta;
import difflib.DiffUtils;
import difflib.Patch;
import difflib.StringUtills;
import hudson.model.Action;
import hudson.plugins.jobConfigHistory.SideBySideView.Line;
import hudson.security.AccessControlled;
import hudson.util.MultipartFormDataParser;
import jenkins.model.Jenkins;

/**
 * Implements some basic methods needed by the
 * {@link JobConfigHistoryRootAction} and {@link JobConfigHistoryProjectAction}.
 *
 * @author Mirko Friedenhagen
 */
public abstract class JobConfigHistoryBaseAction implements Action {

	/**
	 * The jenkins instance.
	 */
	private final Jenkins jenkins;

	/**
	 * Set the {@link Jenkins} instance.
	 */
	public JobConfigHistoryBaseAction() {
		jenkins = Jenkins.getInstance();
	}

	/**
	 * For tests only.
	 *
	 * @param jenkins injected jenkins
	 */
	JobConfigHistoryBaseAction(Jenkins jenkins) {
		this.jenkins = jenkins;
	}

	@Override
	public String getDisplayName() {
		return Messages.displayName();
	}

	@Override
	public String getUrlName() {
		return JobConfigHistoryConsts.URLNAME;
	}

	/**
	 * Returns how the config file should be formatted in configOutput.jelly: as
	 * plain text or xml.
	 *
	 * @return "plain" or "xml"
	 */
	public final String getOutputType() {
		if (("xml").equalsIgnoreCase(getRequestParameter("type"))) {
			return "xml";
		}
		return "plain";
	}

	/**
	 * Checks the url parameter 'timestamp' and returns true if it is parseable as a
	 * date.
	 * 
	 * @param timestamp Timestamp of config change.
	 * @return True if timestamp is okay.
	 */
	protected boolean checkTimestamp(String timestamp) {
		if (timestamp == null || "null".equals(timestamp)) {
			return false;
		}
		PluginUtils.parsedDate(timestamp);
		return true;
	}

	/**
	 * Returns the parameter named {@code parameterName} from current request.
	 *
	 * @param parameterName name of the parameter.
	 * @return value of the request parameter or null if it does not exist.
	 */
	protected String getRequestParameter(final String parameterName) {
		return getCurrentRequest().getParameter(parameterName);
	}

	/**
	 * See whether the current user may read configurations in the object returned
	 * by {@link JobConfigHistoryBaseAction#getAccessControlledObject()}.
	 */
	protected abstract void checkConfigurePermission();

	/**
	 * Returns whether the current user may read configurations in the object
	 * returned by {@link JobConfigHistoryBaseAction#getAccessControlledObject()}.
	 *
	 * @return true if the current user may read configurations.
	 */
	protected abstract boolean hasConfigurePermission();

	/**
	 * Returns the jenkins instance.
	 *
	 * @return the jenkins
	 */
	protected Jenkins getJenkins() {
		return jenkins;
	}

	/**
	 * Returns the object for which we want to provide access control.
	 *
	 * @return the access controlled object.
	 */
	protected abstract AccessControlled getAccessControlledObject();

	/**
	 * Returns side-by-side (i.e. human-readable) diff view lines.
	 *
	 * @param diffLines Unified diff as list of Strings.
	 * @return Nice and clean diff as list of single Lines. if reading one of the
	 *         config files does not succeed.
	 */
	public final List<Line> getDiffLines(List<String> diffLines) throws IOException {
		return new GetDiffLines(diffLines).get();
	}

	/**
	 * Returns a unified diff between two string arrays.
	 *
	 * @param file1      first config file.
	 * @param file2      second config file.
	 * @param file1Lines the lines of the first file.
	 * @param file2Lines the lines of the second file.
	 * @return unified diff
	 */
	protected final String getDiffAsString(final File file1, final File file2, final String[] file1Lines,
			final String[] file2Lines) {
		return getDiffAsString(file1, file2, file1Lines, file2Lines, false, "", "");
	}

    /**
     * Returns a unified diff between two string arrays.
     *
     * @param file1      first config file.
     * @param file2      second config file.
     * @param file1Lines the lines of the first file.
     * @param file2Lines the lines of the second file.
     * @param useRegex determines whether <b>ignoredLinesPattern</b> shall be used.
     * @param ignoredLinesPattern line pairs in which both lines
     *                            match this pattern are deleted.
     * @param ignoredDiffPattern the diff between two lines must
     *                           match this pattern for the line to be deleted.
     * @return unified diff
     */
	protected final String getDiffAsString(final File file1, final File file2, final String[] file1Lines,
			final String[] file2Lines, boolean useRegex, final String ignoredLinesPattern, final String ignoredDiffPattern) {
		final Patch patch = DiffUtils.diff(Arrays.asList(file1Lines), Arrays.asList(file2Lines));
		//TODO figure out something better than the bool-solution

		if (useRegex) {
			//bug/ feature in library: empty deltas are shown, too.
			List<Delta> deltasToBeRemovedAfterTheMainLoop = new LinkedList<Delta>();
			for (Delta delta : patch.getDeltas()) {
			    // Modify both deltas and save the changes.
				List<String> originalLines = Lists.newArrayList((List<String>) delta.getOriginal().getLines());
				List<String> revisedLines = Lists.newArrayList((List<String>) delta.getRevised().getLines());

				for (int line = 0; line < Math.max(originalLines.size(), revisedLines.size()); ++line) {
				    // Delete lines which match the regex from both deltas.

                    //These must be calculated IN the loop because it changes in some iterations.
                    int oriLinesSize = originalLines.size();
                    int revLinesSize = revisedLines.size();


					if (line > oriLinesSize - 1) {
						// line <= revLinesSize-1, because of loop invariant.
						// ori line is empty.
						if (revisedLines.get(line).matches(ignoredLinesPattern)) {
                            //TODO this should be decided by method call, if the functionality is needed
							//this line is needed if a deleted or added line that matches the pattern should be hidden.
							//revisedLines.remove(line);
						}
					} else if (line > revLinesSize - 1) {
						// line <= oriLinesSize-1, because of loop invariant.
						// rev line is empty.
						if (originalLines.get(line).matches(ignoredLinesPattern)) {
							//this line is needed if a deleted or added line that matches the pattern should be hidden.
                            //TODO this should be decided by method call, if the functionality is needed
							//originalLines.remove(line);
						}
					} else {
					    String originalLine = originalLines.get(line);
					    String revisedLine = revisedLines.get(line);
                        String diff = StringUtils.difference(originalLine, revisedLine);
						// both lines are non-empty
						if (originalLine.matches(ignoredLinesPattern)
								&& revisedLine.matches(ignoredLinesPattern)
                                && diff.matches(ignoredDiffPattern)) {
							originalLines.remove(line);
							revisedLines.remove(line);

						}
					}
				}
				if (originalLines.isEmpty() && revisedLines.isEmpty()) {
					//remove the delta from the list.
					deltasToBeRemovedAfterTheMainLoop.add(delta);
				}
				delta.getOriginal().setLines(originalLines);
				delta.getRevised().setLines(revisedLines);
			}
			patch.getDeltas().removeAll(deltasToBeRemovedAfterTheMainLoop);
		}

		final List<String> unifiedDiff = DiffUtils.generateUnifiedDiff(file1.getPath(), file2.getPath(),
				Arrays.asList(file1Lines), patch, 3);
		return StringUtills.join(unifiedDiff, "\n") + "\n";
	}

	/**
	 * Parses the incoming {@literal POST} request and redirects as
	 * {@literal GET showDiffFiles}.
	 *
	 * @param req incoming request
	 * @param rsp outgoing response
	 * @throws ServletException when parsing the request as
	 *                          {@link MultipartFormDataParser} does not succeed.
	 * @throws IOException      when the redirection does not succeed.
	 */
	public void doDiffFiles(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
		String timestamp1 = req.getParameter("timestamp1");
		String timestamp2 = req.getParameter("timestamp2");

		if (PluginUtils.parsedDate(timestamp1).after(PluginUtils.parsedDate(timestamp2))) {
			timestamp1 = req.getParameter("timestamp2");
			timestamp2 = req.getParameter("timestamp1");
		}
		rsp.sendRedirect("showDiffFiles?timestamp1=" + timestamp1 + "&timestamp2=" + timestamp2);
	}

	/**
	 * Action when 'Prev' or 'Next' button in showDiffFiles.jelly is pressed.
	 * Forwards to the previous or next diff.
	 * 
	 * @param req StaplerRequest created by pressing the button
	 * @param rsp Outgoing StaplerResponse
	 * @throws IOException If XML file can't be read
	 */
	public final void doDiffFilesPrevNext(StaplerRequest req, StaplerResponse rsp) throws IOException {
		final String timestamp1 = req.getParameter("timestamp1");
		final String timestamp2 = req.getParameter("timestamp2");
		rsp.sendRedirect("showDiffFiles?timestamp1=" + timestamp1 + "&timestamp2=" + timestamp2);
	}

	/**
	 * Overridable for tests.
	 *
	 * @return current request
	 */
	protected StaplerRequest getCurrentRequest() {
		return Stapler.getCurrentRequest();
	}

	/**
	 * Returns the plugin for tests.
	 *
	 * @return plugin
	 */
	protected JobConfigHistory getPlugin() {
		return PluginUtils.getPlugin();
	}

	/**
	 * For tests.
	 *
	 * @return historyDao
	 */
	protected HistoryDao getHistoryDao() {
		return PluginUtils.getHistoryDao();
	}

}
