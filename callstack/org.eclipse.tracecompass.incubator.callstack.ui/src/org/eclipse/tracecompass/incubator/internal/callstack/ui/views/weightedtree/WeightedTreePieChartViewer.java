/*******************************************************************************
 * Copyright (c) 2019 École Polytechnique de Montréal
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License 2.0 which
 * accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.tracecompass.incubator.internal.callstack.ui.views.weightedtree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.linuxtools.dataviewers.piechart.PieChart;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGBA;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.tracecompass.incubator.analysis.core.weighted.tree.IDataPalette;
import org.eclipse.tracecompass.incubator.analysis.core.weighted.tree.IWeightedTreeProvider;
import org.eclipse.tracecompass.incubator.analysis.core.weighted.tree.IWeightedTreeProvider.DataType;
import org.eclipse.tracecompass.incubator.analysis.core.weighted.tree.IWeightedTreeProvider.MetricType;
import org.eclipse.tracecompass.incubator.analysis.core.weighted.tree.WeightedTree;
import org.eclipse.tracecompass.tmf.core.analysis.IAnalysisModule;
import org.eclipse.tracecompass.tmf.core.model.OutputElementStyle;
import org.eclipse.tracecompass.tmf.core.model.StyleProperties;
import org.eclipse.tracecompass.tmf.core.presentation.RGBAColor;
import org.eclipse.tracecompass.tmf.core.trace.ITmfTrace;
import org.eclipse.tracecompass.tmf.ui.model.StyleManager;
import org.eclipse.tracecompass.tmf.ui.viewers.TmfTimeViewer;
import org.eclipse.tracecompass.tmf.ui.widgets.timegraph.widgets.TimeGraphColorScheme;

/**
 * Creates a viewer containing 2 pie charts, one for showing information about
 * the current selection, and the second one for showing information about the
 * current time-range selection. It follows the MVC pattern, being a view.
 *
 * This class is closely related with the IPieChartViewerState interface that
 * acts as a state machine for the general layout of the charts.
 *
 * @author Geneviève Bastien
 */
public class WeightedTreePieChartViewer extends TmfTimeViewer {

    /**
     * Represents the minimum percentage a slice of pie must have in order to be
     * shown
     */
    private static final float MIN_PRECENTAGE_TO_SHOW_SLICE = 0.01F;// 1%

    /**
     * The pie chart containing global information about the trace
     */
    private @Nullable PieChart fGlobalPC = null;

    /**
     * The listener for the mouse movement event.
     */
    private final Listener fMouseMoveListener;

    /**
     * The listener for the mouse right click event.
     */
    private final MouseListener fMouseClickListener;

    /**
     * The list of listener to notify when an event type is selected
     */
    private ListenerList fSelectedListeners = new ListenerList(ListenerList.IDENTITY);

    private MetricType fWeightType = new MetricType(String.valueOf(Messages.WeightedTreeViewer_Weight), DataType.NUMBER, null);

    /** The color scheme for the chart */
    private TimeGraphColorScheme fColorScheme = new TimeGraphColorScheme();
    private final WeightedTreeView fView;

    private @Nullable PieChartPalette fPiePalette = null;

    private class PieChartPalette {
        private final IDataPalette fPalette;
        private final StyleManager fStyleManager;
        private final Map<OutputElementStyle, Color> fColors = new HashMap<>();

        private PieChartPalette(IDataPalette palette) {
            fPalette = palette;
            fStyleManager = new StyleManager(palette.getStyles());
        }

        public Color getColor(WeightedTree<?> tree) {
            @NonNull OutputElementStyle style = fPalette.getStyleFor(tree);
            return fColors.computeIfAbsent(style, st -> {
                RGBAColor colorStyle = fStyleManager.getColorStyle(st, StyleProperties.BACKGROUND_COLOR);
                if (colorStyle == null) {
                    colorStyle = fStyleManager.getColorStyle(st, StyleProperties.COLOR);
                }
                if (colorStyle == null) {
                    // Return a default color
                    return fColorScheme.getColor(TimeGraphColorScheme.DARK_BLUE_STATE);
                }
                return fColorScheme.getColor(new RGBA(colorStyle.getRed(), colorStyle.getGreen(), colorStyle.getBlue(), colorStyle.getAlpha()));

            });
        }
    }

    /**
     * Constructor
     *
     * @param parent
     *            The parent composite that will hold the viewer
     * @param view
     *            The parent weighted tree view
     */
    public WeightedTreePieChartViewer(@Nullable Composite parent, WeightedTreeView view) {
        super(parent);
        if (parent != null) {
            parent.addDisposeListener(e -> {
                fColorScheme.dispose();
            });
        }
        fView = view;
        // Setup listeners for the tooltips
        fMouseMoveListener = new Listener() {
            @Override
            public void handleEvent(@Nullable Event event) {
                if (event == null) {
                    return;
                }
                PieChart pc = (PieChart) event.widget;
                switch (event.type) {
                /* Get tooltip information on the slice */
                case SWT.MouseMove:
                    int sliceIndex = pc.getSliceIndexFromPosition(0, event.x, event.y);
                    if (sliceIndex < 0) {
                        // mouse is outside the chart
                        pc.setToolTipText(null);
                        break;
                    }
                    float percOfSlice = (float) pc.getSlicePercent(0, sliceIndex);
                    String percent = String.format("%.1f", percOfSlice); //$NON-NLS-1$
                    Long value = Long.valueOf((long) pc.getSeriesSet().getSeries()[sliceIndex].getXSeries()[0]);

                    String text = pc.getSeriesSet().getSeries()[sliceIndex].getId() + "\n"; //$NON-NLS-1$

                    text += fWeightType.format(value) + " (" + percent + "%)"; //$NON-NLS-1$ //$NON-NLS-2$
                    pc.setToolTipText(text);
                    return;
                default:
                }
            }
        };

        fMouseClickListener = new MouseListener() {

            @Override
            public void mouseUp(@Nullable MouseEvent e) {
            }

            @Override
            public void mouseDown(@Nullable MouseEvent e) {
                if (e == null) {
                    return;
                }
                PieChart pc = (PieChart) e.widget;
                int slicenb = pc.getSliceIndexFromPosition(0, e.x, e.y);
                if (slicenb < 0 || slicenb >= pc.getSeriesSet().getSeries().length) {
                    // mouse is outside the chart
                    return;
                }
                Event selectionEvent = new Event();
                selectionEvent.text = pc.getSeriesSet().getSeries()[slicenb].getId();
                notifySelectionListener(selectionEvent);
            }

            @Override
            public void mouseDoubleClick(@Nullable MouseEvent e) {
            }
        };
    }

    // ------------------------------------------------------------------------
    // Class methods
    // ------------------------------------------------------------------------

    @Override
    public void loadTrace(@Nullable ITmfTrace trace) {
        super.loadTrace(trace);
        if (trace == null) {
            return;
        }
        Thread thread = new Thread() {
            @Override
            public void run() {
                initializeDataSource(trace);
            }
        };
        thread.start();
    }

    /**
     * Called by this class' constructor. Constructs the basic viewer containing
     * the charts, as well as their listeners
     */
    private synchronized void initializeDataSource(ITmfTrace trace) {
        Set<IWeightedTreeProvider<?, ?, WeightedTree<?>>> modules = fView.getWeightedTrees(trace);

        modules.forEach(m -> {
            if (m instanceof IAnalysisModule) {
                ((IAnalysisModule) m).schedule();
            }
        });

    }

    /**
     * Updates the data contained in the Global PieChart by using a Map.
     * Normally, this method is only called by the state machine.
     *
     * @param treeProvider
     */
    synchronized void updateGlobalPieChart(Set<WeightedTree<?>> trees, IWeightedTreeProvider<?, ?, WeightedTree<?>> treeProvider) {
        long totalWeight = getTotalWeight(trees);

        PieChart pie = getGlobalPC();
        if (pie == null || totalWeight == 0) {
            pie = createPieChart(treeProvider);
            setGlobalPC(pie);
            fWeightType = treeProvider.getWeightType();
            setPalette(treeProvider.getPalette());
        }

        if (totalWeight > 0) {
            updatePieChartWithData(pie, trees, treeProvider, totalWeight);
        }
        pie.redraw();
    }

    private synchronized void setPalette(IDataPalette palette) {
        PieChartPalette currentPalette = fPiePalette;
        if (currentPalette == null || !palette.equals(currentPalette.fPalette)) {
            fPiePalette = new PieChartPalette(palette);
        }
    }

    private PieChart createPieChart(IWeightedTreeProvider<?, ?, WeightedTree<?>> treeProvider) {
        PieChart pie = new PieChart(getParent(), SWT.NONE);
        Color backgroundColor = fColorScheme.getColor(TimeGraphColorScheme.TOOL_BACKGROUND);
        Color foregroundColor = fColorScheme.getColor(TimeGraphColorScheme.TOOL_FOREGROUND);
        pie.getTitle().setText(treeProvider.getTitle());
        pie.getTitle().setForeground(foregroundColor);
        pie.setBackground(backgroundColor);
        pie.setForeground(foregroundColor);
        // Hide the title over the legend
        pie.getAxisSet().getXAxis(0).getTitle().setText(StringUtils.EMPTY);
        pie.getAxisSet().getXAxis(0).getTitle().setForeground(foregroundColor);
        pie.getLegend().setVisible(true);
        pie.getLegend().setPosition(SWT.RIGHT);
        pie.getLegend().setBackground(backgroundColor);
        pie.getLegend().setForeground(foregroundColor);
        pie.addListener(SWT.MouseMove, fMouseMoveListener);
        pie.addMouseListener(fMouseClickListener);
        return pie;
    }

    /**
     * Calculates the total weight of the trees. If the treeset is empty, or if
     * they all have a weight of 0, then 0 is returned and it means there is no
     * data to display in the pie and it should be reset
     *
     * @param trees
     * @return The total weight. If <code>0</code>, then the pie should be
     *         reset.
     */
    private static long getTotalWeight(Collection<WeightedTree<?>> trees) {
        if (trees.isEmpty()) {
            return 0;
        }
        // Get the total weights
        long totalWeight = 0;
        for (WeightedTree<?> tree : trees) {
            totalWeight += tree.getWeight();
        }
        return totalWeight;
    }

    /**
     * Function used to update or create the slices of a PieChart to match the
     * content of a Map passed in parameter. It also provides a facade to use
     * the PieChart API
     */
    private void updatePieChartWithData(
            final PieChart chart,
            final Collection<WeightedTree<?>> trees,
            IWeightedTreeProvider<?, ?, WeightedTree<?>> treeProvider, long totalWeight) {

        long otherWeight = 0;
        // Add to the list only the trees that would be visible (> threshold),
        // add the rest to an "other" element
        WeightedTree<?> otherTree = null;
        List<WeightedTree<?>> list = new ArrayList<>();
        for (WeightedTree<?> tree : trees) {
            if ((float) tree.getWeight() / (float) totalWeight > MIN_PRECENTAGE_TO_SHOW_SLICE) {
                list.add(tree);
            } else {
                otherWeight += tree.getWeight();
                otherTree = tree;
            }
        }
        Collections.sort(list, Collections.reverseOrder());


        int listSize = otherWeight == 0 ? list.size() : list.size() + 1;
        double[][] sliceValues = new double[listSize][1];
        String[] sliceNames = new String[listSize];
        int i = 0;
        PieChartPalette palette = fPiePalette;
        Color[] colors = (palette == null) ? null : new Color[listSize];

        for (WeightedTree<?> tree : list) {
            sliceNames[i] = treeProvider.toDisplayString(tree);
            sliceValues[i][0] = tree.getWeight();
            if (colors != null && palette != null) {
                colors[i] = palette.getColor(tree);
            }
            i++;
        }
        if (otherWeight != 0) {
            // Compare with the otherTree's weight. If there's only one other,
            // display it as is
            if (otherTree != null && otherTree.getWeight() == otherWeight) {
                sliceNames[list.size()] = treeProvider.toDisplayString(otherTree);
                if (colors != null && palette != null) {
                    colors[list.size()] = palette.getColor(otherTree);
                }
            } else {
                sliceNames[list.size()] = Messages.WeightedTreeViewer_Other;
            }
            sliceValues[list.size()][0] = otherWeight;
        }
        if (colors != null) {
            chart.setCustomColors(colors);
        }
        chart.addPieChartSeries(sliceNames, sliceValues);
    }

    /**
     * Add a selection listener to the pie chart
     *
     * @param l
     *            the listener to add
     */
    public void addSelectionListener(Listener l) {
        fSelectedListeners.add(l);
    }

    /**
     * Remove a selection listener from the pie chart
     *
     * @param l
     *            the listener to remove
     */
    public void removeSelectionListener(Listener l) {
        fSelectedListeners.remove(l);
    }

    /* Notify all listeners that an event type has been selected */
    private void notifySelectionListener(Event e) {
        for (Object o : fSelectedListeners.getListeners()) {
            ((Listener) o).handleEvent(e);
        }
    }

    // ------------------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------------------

    /**
     * @return the global piechart
     */
    synchronized @Nullable PieChart getGlobalPC() {
        return fGlobalPC;
    }

    private synchronized void setGlobalPC(PieChart pie) {
        PieChart pc = fGlobalPC;
        if (pc != null) {
            pc.dispose();
        }
        fGlobalPC = pie;
    }

    // ------------------------------------------------------------------------
    // Setters
    // ------------------------------------------------------------------------

    /**
     * An element has been selected
     *
     * @param trees
     *            The selected elements
     * @param treeProvider
     *            The tree provider for the selected trees
     */
    public void elementSelected(Set<WeightedTree<?>> trees, IWeightedTreeProvider<?, ?, WeightedTree<?>> treeProvider) {
        updateGlobalPieChart(trees, treeProvider);
    }

    @Override
    public @Nullable Control getControl() {
        return getParent();
    }

    @Override
    public void refresh() {

    }

}
