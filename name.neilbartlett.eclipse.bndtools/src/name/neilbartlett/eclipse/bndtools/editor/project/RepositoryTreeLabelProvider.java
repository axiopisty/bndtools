package name.neilbartlett.eclipse.bndtools.editor.project;

import name.neilbartlett.eclipse.bndtools.Plugin;

import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import aQute.bnd.service.RepositoryPlugin;

public class RepositoryTreeLabelProvider extends StyledCellLabelProvider {
	
	Image repoImg = AbstractUIPlugin.imageDescriptorFromPlugin(Plugin.PLUGIN_ID, "/icons/fldr_obj.gif").createImage();
	Image bundleImg = AbstractUIPlugin.imageDescriptorFromPlugin(Plugin.PLUGIN_ID, "/icons/brick.png").createImage();
	
	@Override
	public void update(ViewerCell cell) {
		Object element = cell.getElement();
		if(element instanceof RepositoryPlugin) {
			String name = ((RepositoryPlugin) element).getName();
			cell.setText(name);
			cell.setImage(repoImg);
		} else if(element instanceof RepositoryBundle) {
			RepositoryBundle bundle = (RepositoryBundle) element;
			cell.setText(bundle.getBsn());
			cell.setImage(bundleImg);
		} else if(element instanceof RepositoryBundleVersion) {
			RepositoryBundleVersion bundleVersion = (RepositoryBundleVersion) element;
			StyledString styledString = new StyledString(bundleVersion.getVersion().toString(), StyledString.COUNTER_STYLER);
			cell.setText(styledString.getString());
			cell.setStyleRanges(styledString.getStyleRanges());
		}
	}
	@Override
	public void dispose() {
		super.dispose();
		repoImg.dispose();
		bundleImg.dispose();
	}
}
