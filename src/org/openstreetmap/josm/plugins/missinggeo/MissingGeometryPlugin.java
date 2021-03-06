/*
 *  Copyright 2015 Telenav, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.openstreetmap.josm.plugins.missinggeo;

import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.List;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.gui.IconToggleButton;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.NavigatableComponent;
import org.openstreetmap.josm.gui.NavigatableComponent.ZoomChangeListener;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerAddEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerChangeListener;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerOrderChangeEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerRemoveEvent;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.missinggeo.argument.BoundingBox;
import org.openstreetmap.josm.plugins.missinggeo.argument.ClusterFilter;
import org.openstreetmap.josm.plugins.missinggeo.argument.SearchFilter;
import org.openstreetmap.josm.plugins.missinggeo.argument.TileFilter;
import org.openstreetmap.josm.plugins.missinggeo.entity.Comment;
import org.openstreetmap.josm.plugins.missinggeo.entity.DataSet;
import org.openstreetmap.josm.plugins.missinggeo.entity.Status;
import org.openstreetmap.josm.plugins.missinggeo.entity.Tile;
import org.openstreetmap.josm.plugins.missinggeo.gui.details.MissingGeometryDetailsDialog;
import org.openstreetmap.josm.plugins.missinggeo.gui.layer.MissingGeometryLayer;
import org.openstreetmap.josm.plugins.missinggeo.observer.CommentObserver;
import org.openstreetmap.josm.plugins.missinggeo.util.Util;
import org.openstreetmap.josm.plugins.missinggeo.util.cnf.Config;
import org.openstreetmap.josm.plugins.missinggeo.util.pref.Keys;
import org.openstreetmap.josm.plugins.missinggeo.util.pref.PreferenceManager;
import org.openstreetmap.josm.spi.preferences.PreferenceChangeEvent;
import org.openstreetmap.josm.spi.preferences.PreferenceChangedListener;


/**
 * Defines the main functionality of the missing geometry plugin.
 *
 * @author Beata
 * @version $Revision: 74 $
 */
public class MissingGeometryPlugin extends Plugin
        implements CommentObserver, LayerChangeListener, MouseListener, PreferenceChangedListener, ZoomChangeListener {

    private class DataUpdateThread implements Runnable {

        @Override
        public void run() {
            if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
                final BoundingBox bbox = Util.buildBBox(MainApplication.getMap().mapView);
                if (bbox != null) {
                    final int zoom = Util.zoom();
                    final Class<? extends SearchFilter> filterType =
                            zoom > Config.getInstance().getMaxClusterZoom() ? TileFilter.class : ClusterFilter.class;
                    final SearchFilter searchFilter = PreferenceManager.getInstance().loadSearchFilter(filterType);
                    final DataSet result = ServiceHandler.getInstance().search(bbox, searchFilter, zoom);
                    SwingUtilities.invokeLater(() -> {
                        layer.setDataSet(result);
                        updateSelection(result);
                        layer.invalidate();
                    });
                }
            }
        }

        private void updateSelection(final DataSet result) {
            final Tile tile = layer.lastSelectedTile();
            if (result != null && !GraphicsEnvironment.isHeadless()) {
                if (!result.getClusters().isEmpty()) {
                    dialog.updateData(null, null);
                } else if (tile != null) {
                    if (result.getTiles() != null && result.getTiles().contains(tile)) {
                        dialog.updateTileData(tile);
                    } else {
                        dialog.updateData(null, null);
                    }
                } else {
                    dialog.updateData(null, null);
                }
            }
        }
    }

    /*
     * Enables/disables the left side MissingGeometry window. Also adds the layer if was not already added.
     */
    private class ToggleButtonActionListener implements ActionListener {

        @Override
        public void actionPerformed(final ActionEvent event) {
            if (event.getSource() instanceof IconToggleButton) {
                SwingUtilities.invokeLater(() -> {
                    final IconToggleButton btn = (IconToggleButton) event.getSource();
                    if (btn.isSelected()) {
                        dialog.setVisible(true);
                        btn.setSelected(true);
                    } else {
                        dialog.setVisible(false);
                        btn.setSelected(false);
                        btn.setFocusable(false);
                    }
                    if (layer == null) {
                        registerListeners();
                        addLayer();
                    }
                });
            }

        }
    }


    private MissingGeometryDetailsDialog dialog;
    private MissingGeometryLayer layer;

    /** timer for the zoom in/out operations */
    private Timer zoomTimer;


    /**
     * Builds a new missing geometry plugin. This constructor is automatically invoked by JOSM to bootstrap the plugin.
     *
     * @param pluginInfo a {@code PluginInformation} object.
     */
    public MissingGeometryPlugin(final PluginInformation pluginInfo) {
        super(pluginInfo);
        PreferenceManager.getInstance().saveErrorSupressFlag(false);
    }

    @Override
    public void layerOrderChanged(LayerOrderChangeEvent e) {
        // nothing to add here
    }

    @Override
    public void createComment(final Comment comment) {
        final List<Tile> selectedTiles = layer.getSelectedTiles();
        if (!selectedTiles.isEmpty()) {
            MainApplication.worker.execute(() -> {
                ServiceHandler.getInstance().comment(comment, selectedTiles);
                if (comment.getStatus() != null) {
                    MainApplication.worker.execute(new DataUpdateThread());
                }
                // reload data
                final Status statusFilter = PreferenceManager.getInstance().loadStatusFilter();
                if (comment.getStatus() == null || statusFilter == null || (comment.getStatus() == statusFilter)) {
                    final Tile tile = layer.lastSelectedTile();
                    if (tile != null) {
                        retrieveComment(tile);
                    }
                }
            });
        }
    }

    @Override
    public void layerAdded(final LayerAddEvent e) {
        if (e.getAddedLayer() instanceof MissingGeometryLayer) {
            zoomChanged();
        }
    }

    @Override
    public void layerRemoving(final LayerRemoveEvent e) {
        if (e.getRemovedLayer() instanceof MissingGeometryLayer) {
            NavigatableComponent.removeZoomChangeListener(this);
            MainApplication.getLayerManager().removeLayerChangeListener(this);
            MainApplication.getMap().mapView.removeMouseListener(this);
            Main.pref.removePreferenceChangeListener(this);
            PreferenceManager.getInstance().saveErrorSupressFlag(false);

            // remove toggle action
            SwingUtilities.invokeLater(() -> {
                if (MainApplication.getMap() != null) {
                    MainApplication.getMap().remove(dialog);
                }
                dialog.getButton().setSelected(false);
                dialog.setVisible(false);
                dialog.destroy();
                layer = null;
            });
        }
    }

    @Override
    public void mapFrameInitialized(final MapFrame oldMapFrame, final MapFrame newMapFrame) {
        if (MainApplication.getMap() != null) {
            if (!GraphicsEnvironment.isHeadless()) {
                dialog = new MissingGeometryDetailsDialog();
                newMapFrame.addToggleDialog(dialog);
                dialog.getButton().addActionListener(new ToggleButtonActionListener());
            }
            registerListeners();
            addLayer();
        }
    }

    @Override
    public void mouseClicked(final MouseEvent event) {
        final int zoom = Util.zoom();
        if ((zoom > Config.getInstance().getMaxClusterZoom())
                && (MainApplication.getLayerManager().getActiveLayer() == layer && layer.isVisible())
                && SwingUtilities.isLeftMouseButton(event)) {
            final boolean multiSelect = event.isShiftDown();
            final Tile selectedTile = layer.lastSelectedTile();
            final Tile tile = layer.nearbyTile(event.getPoint(), multiSelect);
            if (tile != null) {
                if (!tile.equals(selectedTile)) {
                    MainApplication.worker.execute(() -> retrieveComment(tile));
                }
            } else if (!multiSelect) {
                updateSelectedData(null, null);
            }
        }
    }

    @Override
    public void mouseEntered(final MouseEvent event) {
        // no logic for this action
    }

    @Override
    public void mouseExited(final MouseEvent event) {
        // no logic for this action
    }

    @Override
    public void mousePressed(final MouseEvent event) {
        // no logic for this action
    }

    @Override
    public void mouseReleased(final MouseEvent event) {
        // no logic for this action
    }

    @Override
    public void preferenceChanged(final PreferenceChangeEvent event) {
        if (event != null && (event.getNewValue() != null && !event.getNewValue().equals(event.getOldValue()))) {
            if (event.getKey().equals(Keys.FILTERS_CHANGED)) {
                MainApplication.worker.execute(new DataUpdateThread());
            }
        }
    }

    @Override
    public void zoomChanged() {
        if (layer != null && layer.isVisible()) {
            if (zoomTimer != null && zoomTimer.isRunning()) {
                zoomTimer.restart();
            } else {
                zoomTimer = new Timer(Config.getInstance().getSearchDelay(), e -> MainApplication.worker.execute(new DataUpdateThread()));
                zoomTimer.setRepeats(false);
                zoomTimer.start();
            }
        }
    }

    private void addLayer() {
        layer = new MissingGeometryLayer();
        MainApplication.getLayerManager().addLayer(layer);
    }

    private void registerListeners() {
        NavigatableComponent.addZoomChangeListener(this);
        MainApplication.getLayerManager().addLayerChangeListener(this);
        if (MainApplication.isDisplayingMapView()) {
            MainApplication.getMap().mapView.addMouseListener(this);
        }
        Main.pref.addPreferenceChangeListener(this);
        if (dialog != null) {
            dialog.registerCommentObserver(this);
        }
    }

    private void retrieveComment(final Tile tile) {
        final List<Comment> comments = ServiceHandler.getInstance().retrieveComments(tile.getX(), tile.getY());
        updateSelectedData(tile, comments);
    }

    private void updateSelectedData(final Tile tile, final List<Comment> comments) {
        SwingUtilities.invokeLater(() -> {
            dialog.updateData(tile, comments);
            layer.updateSelectedTile(tile);
            layer.invalidate();
        });
    }
}
