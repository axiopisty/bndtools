/*******************************************************************************
 * Copyright (c) 2010 Neil Bartlett.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Neil Bartlett - initial API and implementation
 *******************************************************************************/
package bndtools.editor.contents;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.bindings.keys.KeyStroke;
import org.eclipse.jface.bindings.keys.ParseException;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.fieldassist.ContentProposalAdapter;
import org.eclipse.jface.fieldassist.ControlDecoration;
import org.eclipse.jface.fieldassist.FieldDecoration;
import org.eclipse.jface.fieldassist.FieldDecorationRegistry;
import org.eclipse.jface.fieldassist.IContentProposal;
import org.eclipse.jface.fieldassist.IContentProposalListener;
import org.eclipse.jface.fieldassist.TextContentAdapter;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.forms.IManagedForm;
import org.eclipse.ui.forms.IMessageManager;
import org.eclipse.ui.forms.SectionPart;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.forms.events.HyperlinkAdapter;
import org.eclipse.ui.forms.events.HyperlinkEvent;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.forms.widgets.Hyperlink;
import org.eclipse.ui.forms.widgets.Section;
import org.eclipse.ui.ide.ResourceUtil;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.Constants;

import aQute.libg.header.Attrs;
import aQute.libg.version.Version;
import bndtools.BndConstants;
import bndtools.Plugin;
import bndtools.UIConstants;
import bndtools.api.ILogger;
import bndtools.editor.model.BndEditModel;
import bndtools.model.clauses.ExportedPackage;
import bndtools.model.clauses.ServiceComponent;
import bndtools.utils.CachingContentProposalProvider;
import bndtools.utils.JavaContentProposal;
import bndtools.utils.JavaContentProposalLabelProvider;
import bndtools.utils.JavaTypeContentProposal;
import bndtools.utils.ModificationLock;

public class GeneralInfoPart extends SectionPart implements PropertyChangeListener {

    private static final String[] EDITABLE_PROPERTIES = new String[] {
            Constants.BUNDLE_VERSION, Constants.BUNDLE_ACTIVATOR, BndConstants.SOURCES, BndConstants.OUTPUT, aQute.lib.osgi.Constants.SERVICE_COMPONENT, aQute.lib.osgi.Constants.DSANNOTATIONS
    };

    private static final String UNKNOWN_ACTIVATOR_ERROR_KEY = "ERROR_" + Constants.BUNDLE_ACTIVATOR + "_UNKNOWN";
    private static final String UNINCLUDED_ACTIVATOR_WARNING_KEY = "WARNING_" + Constants.BUNDLE_ACTIVATOR + "_UNINCLUDED";

    private static enum ComponentChoice {
        None("None/Undefined"), Bnd("Bnd Annotations"), DS("DS 1.2 Annotations");

        private final String label;

        ComponentChoice(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public static String[] getLabels() {
            ComponentChoice[] values = values();
            String[] labels = new String[values.length];
            for (int i = 0; i < values.length; i++)
                labels[i] = values[i].getLabel();
            return labels;
        }
    }

    @SuppressWarnings("deprecation")
    private static final ServiceComponent SERVICE_COMPONENT_STAR = new ServiceComponent("*", new Attrs());
    private static final String DSANNOTATIONS_STAR = "*";

    private final Set<String> editablePropertySet;
    private final Set<String> dirtySet = new HashSet<String>();

    private BndEditModel model;
    private ComponentChoice componentChoice;

    private Text txtVersion;
    private Text txtActivator;

    private final ModificationLock lock = new ModificationLock();

    private Combo cmbComponents;

    public GeneralInfoPart(Composite parent, FormToolkit toolkit, int style) {
        super(parent, toolkit, style);
        createSection(getSection(), toolkit);

        editablePropertySet = new HashSet<String>();
        for (String prop : EDITABLE_PROPERTIES) {
            editablePropertySet.add(prop);
        }
    }

    private void createSection(Section section, FormToolkit toolkit) {
        section.setText("Basic Information");

        KeyStroke assistKeyStroke = null;
        try {
            assistKeyStroke = KeyStroke.getInstance("Ctrl+Space");
        } catch (ParseException x) {
            // Ignore
        }
        FieldDecoration contentAssistDecoration = FieldDecorationRegistry.getDefault().getFieldDecoration(FieldDecorationRegistry.DEC_CONTENT_PROPOSAL);

        Composite composite = toolkit.createComposite(section);
        section.setClient(composite);

        toolkit.createLabel(composite, "Version:");
        txtVersion = toolkit.createText(composite, "", SWT.BORDER);
        txtVersion.setMessage(Version.LOWEST.toString());

        Hyperlink linkActivator = toolkit.createHyperlink(composite, "Activator:", SWT.NONE);
        txtActivator = toolkit.createText(composite, "", SWT.BORDER);
        txtActivator.setMessage("Enter activator class name");

        Label lblComponents = toolkit.createLabel(composite, "Declarative Services:");
        cmbComponents = new Combo(composite, SWT.READ_ONLY);
        cmbComponents.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false, 1, 1));

        // Content Proposal for the Activator field
        ContentProposalAdapter activatorProposalAdapter = null;

        ActivatorClassProposalProvider proposalProvider = new ActivatorClassProposalProvider();
        activatorProposalAdapter = new ContentProposalAdapter(txtActivator, new TextContentAdapter(), proposalProvider, assistKeyStroke, UIConstants.autoActivationCharacters());
        activatorProposalAdapter.addContentProposalListener(proposalProvider);
        activatorProposalAdapter.setProposalAcceptanceStyle(ContentProposalAdapter.PROPOSAL_REPLACE);
        activatorProposalAdapter.setLabelProvider(new JavaContentProposalLabelProvider());
        activatorProposalAdapter.setAutoActivationDelay(1000);

        // Decorator for the Activator field
        ControlDecoration decorActivator = new ControlDecoration(txtActivator, SWT.LEFT | SWT.CENTER, composite);
        decorActivator.setImage(contentAssistDecoration.getImage());
        decorActivator.setDescriptionText(MessageFormat.format("Content Assist is available. Press {0} or start typing to activate", assistKeyStroke.format()));
        decorActivator.setShowOnlyOnFocus(true);
        decorActivator.setShowHover(true);

        // Decorator for the Components combo
        ControlDecoration decorComponents = new ControlDecoration(cmbComponents, SWT.LEFT | SWT.CENTER, composite);
        decorComponents.setImage(FieldDecorationRegistry.getDefault().getFieldDecoration(FieldDecorationRegistry.DEC_INFORMATION).getImage());
        decorComponents.setDescriptionText("Use Java annotations to detect Declarative Service Components.");
        decorComponents.setShowOnlyOnFocus(false);
        decorComponents.setShowHover(true);

        // Listeners
        txtVersion.addModifyListener(new ModifyListener() {
            public void modifyText(ModifyEvent e) {
                lock.ifNotModifying(new Runnable() {
                    public void run() {
                        addDirtyProperty(Constants.BUNDLE_VERSION);
                    }
                });
            }
        });
        cmbComponents.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                lock.ifNotModifying(new Runnable() {
                    public void run() {
                        ComponentChoice old = componentChoice;

                        int index = cmbComponents.getSelectionIndex();
                        if (index >= 0 && index < ComponentChoice.values().length) {
                            componentChoice = ComponentChoice.values()[cmbComponents.getSelectionIndex()];
                            if (old != componentChoice) {
                                addDirtyProperty(aQute.lib.osgi.Constants.SERVICE_COMPONENT);
                                addDirtyProperty(aQute.lib.osgi.Constants.DSANNOTATIONS);
                                if (old == null) {
                                    cmbComponents.remove(ComponentChoice.values().length);
                                }
                            }
                        }
                    }
                });
            }
        });
        txtActivator.addModifyListener(new ModifyListener() {
            public void modifyText(ModifyEvent ev) {
                lock.ifNotModifying(new Runnable() {
                    public void run() {
                        addDirtyProperty(Constants.BUNDLE_ACTIVATOR);
                    }
                });
                IMessageManager msgs = getManagedForm().getMessageManager();
                String activatorError = null;
                int activatorErrorLevel = IMessageProvider.ERROR;

                String activatorClassName = txtActivator.getText();
                if (activatorClassName != null && activatorClassName.length() > 0) {
                    try {
                        IJavaProject javaProject = getJavaProject();
                        if (javaProject == null) {
                            activatorError = "Cannot validate activator class name, the bnd file is not in a Java project.";
                            activatorErrorLevel = IMessageProvider.WARNING;
                        } else {
                            IType activatorType = javaProject.findType(activatorClassName);
                            if (activatorType == null) {
                                activatorError = "The activator class name is not known in this project.";
                                activatorErrorLevel = IMessageProvider.ERROR;
                            }
                        }
                    } catch (JavaModelException e) {
                        ILogger logger = Plugin.getDefault().getLogger();
                        logger.logError("Error looking up activator class name: " + activatorClassName, e);
                    }
                }

                if (activatorError != null) {
                    msgs.addMessage(UNKNOWN_ACTIVATOR_ERROR_KEY, activatorError, null, activatorErrorLevel, txtActivator);
                } else {
                    msgs.removeMessage(UNKNOWN_ACTIVATOR_ERROR_KEY, txtActivator);
                }

                checkActivatorIncluded();
            }
        });
        linkActivator.addHyperlinkListener(new HyperlinkAdapter() {
            @Override
            public void linkActivated(HyperlinkEvent ev) {
                String activatorClassName = txtActivator.getText();
                if (activatorClassName != null && activatorClassName.length() > 0) {
                    try {
                        IJavaProject javaProject = getJavaProject();
                        if (javaProject == null)
                            return;

                        IType activatorType = javaProject.findType(activatorClassName);
                        if (activatorType != null) {
                            JavaUI.openInEditor(activatorType, true, true);
                        }
                    } catch (PartInitException e) {
                        ErrorDialog.openError(getManagedForm().getForm().getShell(), "Error", null,
                                new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format("Error opening an editor for activator class '{0}'.", activatorClassName), e));
                    } catch (JavaModelException e) {
                        ErrorDialog.openError(getManagedForm().getForm().getShell(), "Error", null, new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format("Error searching for activator class '{0}'.", activatorClassName), e));
                    }
                }
            }
        });
        if (activatorProposalAdapter != null) {
            activatorProposalAdapter.addContentProposalListener(new IContentProposalListener() {
                public void proposalAccepted(IContentProposal proposal) {
                    if (proposal instanceof JavaContentProposal) {
                        String selectedPackageName = ((JavaContentProposal) proposal).getPackageName();
                        if (!model.isIncludedPackage(selectedPackageName)) {
                            model.addPrivatePackage(selectedPackageName);
                        }
                    }
                }
            });
        }

        // Layout
        GridLayout layout = new GridLayout(2, false);
        layout.horizontalSpacing = 10;

        composite.setLayout(layout);

        GridData gd;
        gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.horizontalIndent = 5;
        txtVersion.setLayoutData(gd);

        gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.horizontalIndent = 5;
        txtActivator.setLayoutData(gd);
    }

    void checkActivatorIncluded() {
        String warningMessage = null;
        IAction[] fixes = null;

        String activatorClassName = txtActivator.getText();
        if (activatorClassName != null && activatorClassName.length() > 0) {
            int dotIndex = activatorClassName.lastIndexOf('.');
            if (dotIndex == -1) {
                warningMessage = "Cannot use an activator in the default package.";
            } else {
                final String packageName = activatorClassName.substring(0, dotIndex);
                if (!model.isIncludedPackage(packageName)) {
                    warningMessage = "Activator package is not included in the bundle. It will be imported instead.";
                    fixes = new Action[] {
                            new Action(MessageFormat.format("Add \"{0}\" to Private Packages.", packageName)) {
                                @Override
                                public void run() {
                                    model.addPrivatePackage(packageName);
                                    addDirtyProperty(aQute.lib.osgi.Constants.PRIVATE_PACKAGE);
                                };
                            }, new Action(MessageFormat.format("Add \"{0}\" to Exported Packages.", packageName)) {
                                @Override
                                public void run() {
                                    model.addExportedPackage(new ExportedPackage(packageName, null));
                                    addDirtyProperty(aQute.lib.osgi.Constants.PRIVATE_PACKAGE);
                                };
                            }
                    };
                }
            }
        }

        IMessageManager msgs = getManagedForm().getMessageManager();
        if (warningMessage != null)
            msgs.addMessage(UNINCLUDED_ACTIVATOR_WARNING_KEY, warningMessage, fixes, IMessageProvider.WARNING, txtActivator);
        else
            msgs.removeMessage(UNINCLUDED_ACTIVATOR_WARNING_KEY, txtActivator);
    }

    protected void addDirtyProperty(final String property) {
        lock.ifNotModifying(new Runnable() {
            public void run() {
                dirtySet.add(property);
                getManagedForm().dirtyStateChanged();
            }
        });
    }

    @Override
    public void markDirty() {
        throw new UnsupportedOperationException("Do not call markDirty directly, instead call addDirtyProperty.");
    }

    @Override
    public boolean isDirty() {
        return !dirtySet.isEmpty();
    }

    @Override
    public void commit(boolean onSave) {
        try {
            // Stop listening to property changes during the commit only
            model.removePropertyChangeListener(this);
            if (dirtySet.contains(Constants.BUNDLE_VERSION)) {
                String version = txtVersion.getText();
                if (version != null && version.length() == 0)
                    version = null;
                model.setBundleVersion(version);
            }
            if (dirtySet.contains(Constants.BUNDLE_ACTIVATOR)) {
                String activator = txtActivator.getText();
                if (activator != null && activator.length() == 0)
                    activator = null;
                model.setBundleActivator(activator);
            }

            if (dirtySet.contains(aQute.lib.osgi.Constants.SERVICE_COMPONENT) || dirtySet.contains(aQute.lib.osgi.Constants.DSANNOTATIONS)) {
                switch (componentChoice) {
                case Bnd :
                    model.setServiceComponents(Collections.singletonList(SERVICE_COMPONENT_STAR));
                    model.setDSAnnotationPatterns(null);
                    break;
                case DS :
                    model.setServiceComponents(null);
                    model.setDSAnnotationPatterns(Collections.singletonList(DSANNOTATIONS_STAR));
                    break;
                default :
                    model.setServiceComponents(null);
                    model.setDSAnnotationPatterns(null);
                }
            }
        } finally {
            // Restore property change listening
            model.addPropertyChangeListener(this);
            dirtySet.clear();
            getManagedForm().dirtyStateChanged();
        }
    }

    @Override
    public void refresh() {
        super.refresh();
        lock.modifyOperation(new Runnable() {
            public void run() {
                String bundleVersion = model.getBundleVersionString();
                txtVersion.setText(bundleVersion != null ? bundleVersion : ""); //$NON-NLS-1$

                String bundleActivator = model.getBundleActivator();
                txtActivator.setText(bundleActivator != null ? bundleActivator : ""); //$NON-NLS-1$

                List<ServiceComponent> bndPatterns = model.getServiceComponents();
                List<String> dsPatterns = model.getDSAnnotationPatterns();

                if (bndPatterns != null && bndPatterns.size() == 1 && SERVICE_COMPONENT_STAR.equals(bndPatterns.get(0)) && dsPatterns == null) {
                    componentChoice = ComponentChoice.Bnd;
                } else if (dsPatterns != null && dsPatterns.size() == 1 && DSANNOTATIONS_STAR.equals(dsPatterns.get(0)) && bndPatterns == null) {
                    componentChoice = ComponentChoice.DS;
                } else if (bndPatterns == null && dsPatterns == null) {
                    componentChoice = ComponentChoice.None;
                } else {
                    componentChoice = null;
                }

                cmbComponents.setItems(ComponentChoice.getLabels());
                if (componentChoice == null) {
                    cmbComponents.add("Manually Defined", ComponentChoice.values().length);
                    cmbComponents.select(ComponentChoice.values().length);
                } else {
                    cmbComponents.select(componentChoice.ordinal());
                }
            }
        });
        dirtySet.clear();
        getManagedForm().dirtyStateChanged();
    }

    @Override
    public void initialize(IManagedForm form) {
        super.initialize(form);

        this.model = (BndEditModel) form.getInput();
        this.model.addPropertyChangeListener(this);
    }

    public void propertyChange(PropertyChangeEvent evt) {
        if (editablePropertySet.contains(evt.getPropertyName())) {
            IFormPage page = (IFormPage) getManagedForm().getContainer();
            if (page.isActive()) {
                refresh();
            } else {
                markStale();
            }
        } else if (Constants.EXPORT_PACKAGE.equals(evt.getPropertyName()) || aQute.lib.osgi.Constants.PRIVATE_PACKAGE.equals(evt.getPropertyName())) {
            checkActivatorIncluded();
        }
    }

    @Override
    public void dispose() {
        super.dispose();
        if (this.model != null)
            this.model.removePropertyChangeListener(this);
    }

    IJavaProject getJavaProject() {
        IFormPage formPage = (IFormPage) getManagedForm().getContainer();
        IFile file = ResourceUtil.getFile(formPage.getEditorInput());
        return file != null ? JavaCore.create(file.getProject()) : null;
    }

    private class ActivatorClassProposalProvider extends CachingContentProposalProvider {
        @Override
        protected List<IContentProposal> doGenerateProposals(String contents, int position) {
            final String prefix = contents.substring(0, position);
            final IJavaProject javaProject = getJavaProject();
            if (javaProject == null)
                return Collections.emptyList();

            try {
                final List<IContentProposal> result = new ArrayList<IContentProposal>();

                final IRunnableWithProgress runnable = new IRunnableWithProgress() {
                    public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                        try {
                            IType activatorType = javaProject.findType(BundleActivator.class.getName());
                            if (activatorType != null) {
                                ITypeHierarchy hierarchy = activatorType.newTypeHierarchy(javaProject, monitor);
                                for (IType subType : hierarchy.getAllSubtypes(activatorType)) {
                                    if (!Flags.isAbstract(subType.getFlags()) && subType.getElementName().toLowerCase().contains(prefix.toLowerCase())) {
                                        result.add(new JavaTypeContentProposal(subType));
                                    }
                                }
                            }
                        } catch (JavaModelException e) {
                            throw new InvocationTargetException(e);
                        }
                    }
                };

                IWorkbenchWindow window = ((IFormPage) getManagedForm().getContainer()).getEditorSite().getWorkbenchWindow();
                window.run(false, false, runnable);
                return result;
            } catch (InvocationTargetException e) {
                IStatus status = new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error searching for BundleActivator types", e.getTargetException());
                Plugin.log(status);
                return Collections.emptyList();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Collections.emptyList();
            }
        }

        @Override
        protected boolean match(String contents, int position, IContentProposal proposal) {
            String prefix = contents.substring(0, position);
            return ((JavaTypeContentProposal) proposal).getTypeName().toLowerCase().startsWith(prefix.toLowerCase());
        }
    }
}
