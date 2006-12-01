/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.jcr2spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.jackrabbit.test.NotExecutableException;
import org.apache.jackrabbit.test.AbstractJCRTest;

import javax.jcr.Value;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.InvalidItemStateException;
import javax.jcr.version.VersionException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.lock.LockException;

/**
 * <code>RefreshTrue</code>...
 */
public class RefreshTrue extends AbstractJCRTest {

    private static Logger log = LoggerFactory.getLogger(RefreshTrue.class);

    private Value testValue;

    protected void setUp() throws Exception {
        super.setUp();

        testValue = testRootNode.getSession().getValueFactory().createValue("anyString");
        if (!testRootNode.getPrimaryNodeType().canSetProperty(propertyName1, testValue)) {
            throw new NotExecutableException("");
        }
    }

    public void testNewProperty() throws RepositoryException {
        Property p = testRootNode.setProperty(propertyName1, testValue);
        testRootNode.refresh(true);

        // p must still be accessible
        p.getString();
        assertTrue("Refresh 'true' must not affect a new Property.", testRootNode.hasProperty(propertyName1));
        Property pAgain = testRootNode.getProperty(propertyName1);
        assertTrue("Refresh 'true' must not affect a new Property.", p.isSame(pAgain));
    }

    public void testRemovedProperty() throws RepositoryException, LockException, ConstraintViolationException, VersionException {
        Property p = testRootNode.setProperty(propertyName1, testValue);
        testRootNode.save();

        p.remove();
        testRootNode.refresh(true);

        // Property p must remain removed
        try {
            p.getString();
            fail("Refresh 'true' must not revert removal of an item.");
        } catch (InvalidItemStateException e) {
            //ok
        }
        assertFalse("Refresh 'true' must not revert removal of an item.", testRootNode.hasProperty(propertyName1));
    }
}