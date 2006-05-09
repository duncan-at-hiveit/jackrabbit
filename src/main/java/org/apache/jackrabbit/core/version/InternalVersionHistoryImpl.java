/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.version;

import org.apache.jackrabbit.core.NodeImpl;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.PropertyState;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.uuid.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.PropertyType;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.version.VersionException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Implements a <code>InternalVersionHistory</code>
 */
class InternalVersionHistoryImpl extends InternalVersionItemImpl
        implements InternalVersionHistory {

    /**
     * default logger
     */
    private static Logger log = LoggerFactory.getLogger(InternalVersionHistory.class);

    /**
     * the cache of the version labels
     * key = version label (String)
     * value = version
     */
    private HashMap labelCache = new HashMap();

    /**
     * the root version of this history
     */
    private InternalVersion rootVersion;

    /**
     * the hashmap of all versions
     * key = versionId (NodeId)
     * value = version
     */
    private HashMap versionCache = new HashMap();

    /**
     * Temporary version cache, used on a refresh.
     */
    private HashMap tempVersionCache = new HashMap();

    /**
     * The nodes state of this version history
     */
    private NodeStateEx node;

    /**
     * the node that holds the label nodes
     */
    private NodeStateEx labelNode;

    /**
     * the id of this history
     */
    private NodeId historyId;

    /**
     * the id of the versionable node
     */
    private NodeId versionableId;

    /**
     * Creates a new VersionHistory object for the given node state.
     */
    public InternalVersionHistoryImpl(AbstractVersionManager vMgr, NodeStateEx node)
            throws RepositoryException {
        super(vMgr);
        this.node = node;
        init();
    }

    /**
     * Initialies the history and loads all internal caches
     *
     * @throws RepositoryException
     */
    private void init() throws RepositoryException {
        versionCache.clear();
        labelCache.clear();

        // get id
        historyId = node.getNodeId();

        // get versionable id
        versionableId = NodeId.valueOf(node.getPropertyValue(QName.JCR_VERSIONABLEUUID).toString());

        // get entries
        NodeStateEx[] children = node.getChildNodes();
        for (int i = 0; i < children.length; i++) {
            NodeStateEx child = children[i];
            if (child.getName().equals(QName.JCR_VERSIONLABELS)) {
                labelNode = child;
                continue;
            }
            InternalVersionImpl v = createVersionInstance(child);
            versionCache.put(v.getId(), v);
            if (v.isRootVersion()) {
                rootVersion = v;
            }
            vMgr.versionCreated(v);
        }

        // check for legacy version nodes that had 'virtual' jcr:successor property
        if (rootVersion.getSuccessors().length==0 && versionCache.size()>1) {
            // resolve successors and predecessors
            Iterator iter = versionCache.values().iterator();
            while (iter.hasNext()) {
                InternalVersionImpl v = (InternalVersionImpl) iter.next();
                v.legacyResolveSuccessors();
            }
        }

        try {
            // init label cache
            PropertyState[] labels = labelNode.getProperties();
            for (int i = 0; i < labels.length; i++) {
                PropertyState pState = labels[i];
                if (pState.getType() == PropertyType.REFERENCE) {
                    QName name = pState.getName();
                    UUID ref = (UUID) pState.getValues()[0].internalValue();
                    InternalVersionImpl v = (InternalVersionImpl) getVersion(new NodeId(ref));
                    if (v != null) {
                        labelCache.put(name, v);
                        v.internalAddLabel(name);
                    } else {
                        log.warn("Error while resolving label reference. Version missing: " + ref);
                    }
                }
            }
        } catch (ItemStateException e) {
            throw new RepositoryException(e);
        }
    }

    /**
     * Reload this object and all its dependent version objects.
     */
    void reload() throws RepositoryException {
        tempVersionCache.putAll(versionCache);

        init();

        // invalidate all versions that are not referenced any more
        Iterator iter = tempVersionCache.values().iterator();
        while (iter.hasNext()) {
            InternalVersionImpl v = (InternalVersionImpl) iter.next();
            v.invalidate();
        }
        tempVersionCache.clear();
    }

    /**
     * Create a version instance. May resurrect versions temporarily swapped
     * out when refreshing this history.
     */
    InternalVersionImpl createVersionInstance(NodeStateEx child) {
        InternalVersionImpl v = (InternalVersionImpl) tempVersionCache.remove(child.getNodeId());
        if (v != null) {
            v.clear();
        } else {
            v = new InternalVersionImpl(this, child, child.getName());
        }
        return v;
    }

    /**
     * {@inheritDoc}
     */
    public NodeId getId() {
        return historyId;
    }

    /**
     * {@inheritDoc}
     */
    public InternalVersionItem getParent() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public InternalVersion getRootVersion() {
        return rootVersion;
    }

    /**
     * {@inheritDoc}
     */
    public InternalVersion getVersion(QName versionName) throws VersionException {
        // maybe add cache by name?
        Iterator iter = versionCache.values().iterator();
        while (iter.hasNext()) {
            InternalVersion v = (InternalVersion) iter.next();
            if (v.getName().equals(versionName)) {
                return v;
            }
        }
        throw new VersionException("Version " + versionName + " does not exist.");
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasVersion(QName versionName) {
        // maybe add cache?
        Iterator iter = versionCache.values().iterator();
        while (iter.hasNext()) {
            InternalVersion v = (InternalVersion) iter.next();
            if (v.getName().equals(versionName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasVersion(NodeId id) {
        return versionCache.containsKey(id);
    }

    /**
     * {@inheritDoc}
     */
    public InternalVersion getVersion(NodeId id) {
        return (InternalVersion) versionCache.get(id);
    }

    /**
     * {@inheritDoc}
     */
    public InternalVersion getVersionByLabel(QName label) {
        return (InternalVersion) labelCache.get(label);
    }

    /**
     * {@inheritDoc}
     */
    public Iterator getVersions() {
        return versionCache.values().iterator();
    }

    /**
     * {@inheritDoc}
     */
    public int getNumVersions() {
        return versionCache.size();
    }

    /**
     * {@inheritDoc}
     */
    public UUID getVersionableUUID() {
        return versionableId.getUUID();
    }

    /**
     * {@inheritDoc}
     */
    public QName[] getVersionLabels() {
        return (QName[]) labelCache.keySet().toArray(new QName[labelCache.size()]);
    }

    /**
     * {@inheritDoc}
     */
    public NodeId getVersionLabelsId() {
        return labelNode.getNodeId();
    }

    /**
     * Removes the indicated version from this VersionHistory. If the specified
     * vesion does not exist, if it specifies the root version or if it is
     * referenced by any node e.g. as base version, a VersionException is thrown.
     * <p/>
     * all successors of the removed version become successors of the
     * predecessors of the removed version and vice versa. then, the entire
     * version node and all its subnodes are removed.
     *
     * @param versionName
     * @throws VersionException
     */
    void removeVersion(QName versionName) throws RepositoryException {

        InternalVersionImpl v = (InternalVersionImpl) getVersion(versionName);
        if (v.equals(rootVersion)) {
            String msg = "Removal of " + versionName + " not allowed.";
            log.debug(msg);
            throw new VersionException(msg);
        }
        // check if any references (from outside the version storage) exist on this version
        List refs = vMgr.getItemReferences(v);
        if (!refs.isEmpty()) {
            throw new ReferentialIntegrityException("Unable to remove version. At least once referenced.");
        }

        // unregister from labels
        QName[] labels = v.internalGetLabels();
        for (int i = 0; i < labels.length; i++) {
            v.internalRemoveLabel(labels[i]);
            labelNode.removeProperty(labels[i]);
        }
        // detach from the version graph
        v.internalDetach();

        // remove from persistance state
        node.removeNode(v.getName());

        // and remove from history
        versionCache.remove(v.getId());
        vMgr.versionDestroyed(v);

        // store changes
        node.store();
    }

    /**
     * Sets the version <code>label</code> to the given <code>version</code>.
     * If the label is already assigned to another version, a VersionException is
     * thrown unless <code>move</code> is <code>true</code>. If <code>version</code>
     * is <code>null</code>, the label is removed from the respective version.
     * In either case, the version the label was previously assigned to is returned,
     * or <code>null</code> of the label was not moved.
     *
     * @param versionName the name of the version
     * @param label the label to assgign
     * @param move  flag what to do by collisions
     * @return the version that was previously assigned by this label or <code>null</code>.
     * @throws VersionException
     */
    InternalVersion setVersionLabel(QName versionName, QName label, boolean move)
            throws VersionException {

        InternalVersion version =
            (versionName != null) ? getVersion(versionName) : null;
        if (versionName != null && version == null) {
            throw new VersionException("Version " + versionName + " does not exist in this version history.");
        }
        InternalVersionImpl prev = (InternalVersionImpl) labelCache.get(label);
        if (prev == null) {
            if (version == null) {
                return null;
            }
        } else {
            if (prev.equals(version)) {
                return version;
            } else if (!move) {
                // already defined elsewhere, throw
                throw new VersionException("Version label " + label + " already defined for version " + prev.getName());
            }
        }

        // update persistence
        try {
            if (version == null) {
                labelNode.removeProperty(label);
            } else {
                labelNode.setPropertyValue(label, InternalValue.create(version.getId().getUUID()));
            }
            labelNode.store();
        } catch (RepositoryException e) {
            throw new VersionException(e);
        }

        // update internal structures
        if (prev != null) {
            prev.internalRemoveLabel(label);
            labelCache.remove(label);
        }
        if (version != null) {
            labelCache.put(label, version);
            ((InternalVersionImpl) version).internalAddLabel(label);
        }
        return prev;
    }

    /**
     * Checks in a node. It creates a new version with the given name and freezes
     * the state of the given node.
     *
     * @param name
     * @param src
     * @return
     * @throws RepositoryException
     */
    InternalVersionImpl checkin(QName name, NodeImpl src)
            throws RepositoryException {

        // copy predecessors from src node
        Value[] preds = src.getProperty(QName.JCR_PREDECESSORS).getValues();
        InternalValue[] predecessors = new InternalValue[preds.length];
        for (int i = 0; i < preds.length; i++) {
            UUID predId = UUID.fromString(preds[i].getString());
            // check if version exist
            if (!versionCache.containsKey(new NodeId(predId))) {
                throw new RepositoryException("invalid predecessor in source node");
            }
            predecessors[i] = InternalValue.create(predId);
        }

        NodeId versionId = new NodeId(UUID.randomUUID());
        NodeStateEx vNode = node.addNode(name, QName.NT_VERSION, versionId, true);

        // initialize 'created', 'predecessors' and 'successors'
        vNode.setPropertyValue(QName.JCR_CREATED, InternalValue.create(Calendar.getInstance()));
        vNode.setPropertyValues(QName.JCR_PREDECESSORS, PropertyType.REFERENCE, predecessors);
        vNode.setPropertyValues(QName.JCR_SUCCESSORS, PropertyType.REFERENCE, InternalValue.EMPTY_ARRAY);

        // checkin source node
        InternalFrozenNodeImpl.checkin(vNode, QName.JCR_FROZENNODE, src);

        // update version graph
        InternalVersionImpl version = new InternalVersionImpl(this, vNode, name);
        version.internalAttach();

        // and store
        node.store();

        vMgr.versionCreated(version);

        // update cache
        versionCache.put(version.getId(), version);

        return version;
    }

    /**
     * Creates a new <code>InternalVersionHistory</code> below the given parent
     * node and with the given name.
     *
     * @param parent
     * @param name
     * @return
     * @throws RepositoryException
     */
    static InternalVersionHistoryImpl create(AbstractVersionManager vMgr,
                                             NodeStateEx parent,
                                             NodeId historyId, QName name,
                                             NodeState nodeState)
            throws RepositoryException {

        // create history node
        NodeStateEx pNode = parent.addNode(name, QName.NT_VERSIONHISTORY, historyId, true);

        // set the versionable uuid
        String versionableUUID = nodeState.getNodeId().getUUID().toString();
        pNode.setPropertyValue(QName.JCR_VERSIONABLEUUID, InternalValue.create(versionableUUID));

        // create label node
        pNode.addNode(QName.JCR_VERSIONLABELS, QName.NT_VERSIONLABELS, null, false);

        // create root version
        NodeId versionId = new NodeId(UUID.randomUUID());
        NodeStateEx vNode = pNode.addNode(QName.JCR_ROOTVERSION, QName.NT_VERSION, versionId, true);

        // initialize 'created' and 'predecessors'
        vNode.setPropertyValue(QName.JCR_CREATED, InternalValue.create(Calendar.getInstance()));
        vNode.setPropertyValues(QName.JCR_PREDECESSORS, PropertyType.REFERENCE, InternalValue.EMPTY_ARRAY);
        vNode.setPropertyValues(QName.JCR_SUCCESSORS, PropertyType.REFERENCE, InternalValue.EMPTY_ARRAY);

        // add also an empty frozen node to the root version
        NodeStateEx node = vNode.addNode(QName.JCR_FROZENNODE, QName.NT_FROZENNODE, null, true);

        // initialize the internal properties
        node.setPropertyValue(QName.JCR_FROZENUUID, InternalValue.create(versionableUUID));
        node.setPropertyValue(QName.JCR_FROZENPRIMARYTYPE,
                InternalValue.create(nodeState.getNodeTypeName()));

        Set mixins = nodeState.getMixinTypeNames();
        if (mixins.size() > 0) {
            InternalValue[] ivalues = new InternalValue[mixins.size()];
            Iterator iter = mixins.iterator();
            for (int i = 0; i < mixins.size(); i++) {
                ivalues[i] = InternalValue.create((QName) iter.next());
            }
            node.setPropertyValues(QName.JCR_FROZENMIXINTYPES, PropertyType.NAME, ivalues);
        }

        parent.store();
        return new InternalVersionHistoryImpl(vMgr, pNode);
    }
}
