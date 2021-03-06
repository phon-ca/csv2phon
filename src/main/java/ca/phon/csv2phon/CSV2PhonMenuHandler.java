/*
 * Copyright (C) 2012-2018 Gregory Hedlund
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 *    http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.phon.csv2phon;

import java.awt.Window;

import javax.swing.JMenuBar;

import ca.phon.app.project.ProjectWindow;
import ca.phon.csv2phon.wizard.CSVImportWizard;
import ca.phon.plugin.*;
import ca.phon.project.Project;
import ca.phon.ui.CommonModuleFrame;
import ca.phon.ui.action.*;
import ca.phon.ui.menu.MenuBuilder;

@PhonPlugin(name="csv2phon",
	author="Greg J. Hedlund",
	version="15",
	minPhonVersion="2.2.0")
public class CSV2PhonMenuHandler 
	implements IPluginMenuFilter, IPluginExtensionPoint<IPluginMenuFilter> {

	@Override
	public Class<?> getExtensionType() {
		return IPluginMenuFilter.class;
	}

	@Override
	public IPluginExtensionFactory<IPluginMenuFilter> getFactory() {
		return new IPluginExtensionFactory<IPluginMenuFilter>() {
			
			@Override
			public IPluginMenuFilter createObject(Object... args) {
				return CSV2PhonMenuHandler.this;
			}
			
		};
	}

	@Override
	public void filterWindowMenu(Window owner, JMenuBar menuBar) {
		if(!(owner instanceof ProjectWindow)) return;
		final CommonModuleFrame cmf = (CommonModuleFrame)owner;
		
		final MenuBuilder builder = new MenuBuilder(menuBar);
		builder.addSeparator("./Tools", "csv2phon");
		
		final PhonUIAction csv2PhonAct = new PhonUIAction(CSV2PhonMenuHandler.class, "csv2PhonWizard");
		csv2PhonAct.setData(cmf);
		csv2PhonAct.putValue(PhonUIAction.NAME, "Import from CSV...");
		csv2PhonAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Import CSV files as new Sessions in the current project.");
		builder.addItem("./Tools@csv2phon", csv2PhonAct);
	}
	
	public static void csv2PhonWizard(PhonActionEvent pae) {
		final ProjectWindow pw = (ProjectWindow)pae.getData();
		final Project project = pw.getProject();
		if(project == null) return;
		
		final CSVImportWizard wizard = new CSVImportWizard(project);
		wizard.pack();
		wizard.centerWindow();
		wizard.showWizard();
	}

}
