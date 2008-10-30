/**
 * Copyright (c) 2008 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 * 
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation. 
 */
package com.redhat.rhn.domain.kickstart;

import com.redhat.rhn.common.hibernate.HibernateFactory;
import com.redhat.rhn.common.hibernate.HibernateRuntimeException;
import com.redhat.rhn.domain.kickstart.crypto.CryptoKey;
import com.redhat.rhn.domain.kickstart.crypto.CryptoKeyType;
import com.redhat.rhn.domain.org.Org;

import org.apache.log4j.Logger;
import org.hibernate.HibernateException;
import org.hibernate.Query;
import org.hibernate.Session;

import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * KickstartFactory
 * @version $Rev$
 */
public class KickstartFactory extends HibernateFactory {
    
    
    private static KickstartFactory singleton = new KickstartFactory();
    private static Logger log = Logger.getLogger(KickstartFactory.class);
    

    public static final CryptoKeyType KEY_TYPE_GPG = lookupKeyType("GPG");
    public static final CryptoKeyType KEY_TYPE_SSL = lookupKeyType("SSL");
    public static final KickstartSessionState SESSION_STATE_FAILED = 
        lookupSessionStateByLabel("failed");
    public static final KickstartSessionState SESSION_STATE_CREATED = 
        lookupSessionStateByLabel("created");
    public static final KickstartSessionState SESSION_STATE_STARTED = 
        lookupSessionStateByLabel("started");
    public static final KickstartSessionState SESSION_STATE_COMPLETE = 
        lookupSessionStateByLabel("complete");
    public static final KickstartSessionState SESSION_STATE_CONFIG_ACCESSED =
        lookupSessionStateByLabel("configuration_accessed");
    
    private static final String KICKSTART_CANCELLED_MESSAGE = 
        "Kickstart cancelled due to action removal";
    
    public static final KickstartTreeType TREE_TYPE_EXTERNAL = 
        lookupKickstartTreeTypeByLabel("externally-managed");
    
    private KickstartFactory() {
        super();
    }


    /**
     * Get the Logger for the derived class so log messages
     * show up on the correct class
     */
    protected Logger getLogger() {
        return log;
    }

    private static CryptoKeyType lookupKeyType(String label) {
        return (CryptoKeyType) HibernateFactory.getSession()
                                          .getNamedQuery("CryptoKeyType.findByLabel")
                                          .setString("label", label)
                                          .uniqueResult();
    }
    
    /**
     * 
     * @param ksid Kickstart Data Id to lookup
     * @param orgIn Org associated with Kickstart Data
     * @return Kickstart Data object by ksid
     */
    public static KickstartData lookupKickstartDataByIdAndOrg(Org orgIn, Long ksid) {
        return (KickstartData)  HibernateFactory.getSession()
                                      .getNamedQuery("KickstartData.findByIdAndOrg")
                                      .setLong("id", ksid.longValue())
                                      .setLong("org_id", orgIn.getId().longValue())
                                      .uniqueResult();
    }
    
    /**
     * Lookup a KickstartData based on a label and orgId
     * @param label to lookup
     * @param orgId who owns KickstartData
     * @return KickstartData if found, null if not
     */
    public static KickstartData lookupKickstartDataByLabelAndOrgId(
            String label, Long orgId) {
        return (KickstartData) HibernateFactory.getSession().
                                      getNamedQuery("KickstartData.findByLabelAndOrg")
                                      .setString("label", label)
                                      .setLong("org_id", orgId.longValue())
                                      .uniqueResult();
    }
    
    private static List<KickstartCommandName> lookupKickstartCommandNames(
            KickstartData ksdata, boolean onlyAdvancedOptions) {
        
        Session session = null;
        List names = null;
        
        String query = "KickstartCommandName.listAllOptions";
        if (onlyAdvancedOptions) {
            query = "KickstartCommandName.listAdvancedOptions";
        }
        
        session = HibernateFactory.getSession();
        names = session.getNamedQuery(query).setCacheable(true).list();            
        
        // Filter out the unsupported Commands for the passed in profile
        List<KickstartCommandName> retval = new LinkedList<KickstartCommandName>();
        Iterator i = names.iterator();
        while (i.hasNext()) {
            KickstartCommandName cn = (KickstartCommandName) i.next();
            if (cn.getName().equals("selinux") && ksdata.isLegacyKickstart()) {
                continue;
            }
            else if (cn.getName().equals("lilocheck") && !ksdata.isPreRHEL5Kickstart()) {
                continue;
            } 
            else {
                retval.add(cn);
            }
        }
        
        return retval;
    }
    
    /**
     * Get the list of KickstartCommandName objects that are supportable by
     * the passed in KickstartData.  Filters out unsupported commands such as
     * 'selinux' for RHEL2/3
     * 
     * @param ksdata KickstartData object to check compatibility with  
     * @return List of advanced KickstartCommandNames. Does not include partitions, 
     * logvols, raids, varlogs or includes which is displayed sep in the UI. 
     */
    public static List<KickstartCommandName> lookupKickstartCommandNames(
            KickstartData ksdata) {
        
        return lookupKickstartCommandNames(ksdata, true);
    }
    
    /**
     * Get the list of KickstartCommandName objects that are supportable by
     * the passed in KickstartData.  Filters out unsupported commands such as
     * 'selinux' for RHEL2/3
     * 
     * @param ksdata KickstartData object to check compatibility with  
     * @return List of  KickstartCommandNames.
     */
    public static List<KickstartCommandName> lookupAllKickstartCommandNames(
            KickstartData ksdata) {
        
        return lookupKickstartCommandNames(ksdata, false);
    }
    
    /**
     * Looks up a specific KickstartCommandName
     * @param commandName name of the KickstartCommandName
     * @return found instance, if any
     */
    public static KickstartCommandName lookupKickstartCommandName(String commandName) {
        Session session = null;
        KickstartCommandName retval = null;
        session = HibernateFactory.getSession();
        Query query = 
            session.getNamedQuery(KickstartQueries.KICKSTART_CMD_FIND_BY_LABEL);
        //Retrieve from cache if there
        query.setCacheable(true);
        query.setParameter("name", commandName);
        retval = (KickstartCommandName) query.uniqueResult();
        return retval;
        
    }
    
    /**
     * Create a new KickstartCommand object
     * @param ksdata to associate with
     * @param nameIn of KickstartCommand
     * @return KickstartCommand created
     * @throws Exception
     */
    public static KickstartCommand createKickstartCommand(KickstartData ksdata, 
            String nameIn) {
        KickstartCommand retval = new KickstartCommand();
        KickstartCommandName name =
            KickstartFactory.lookupKickstartCommandName(nameIn);
        retval = new KickstartCommand();
        retval.setCommandName(name);
        retval.setKickstartData(ksdata);
        retval.setCreated(new Date());
        retval.setModified(new Date());
        ksdata.addCommand(retval);
        return retval;
    }
    

    
    /**
     * 
     * @return List of required advanced Kickstart Command Names. Does not include 
     * partitions, logvols, raids, varlogs or includes. 
     */
    public static List lookupKickstartRequiredOptions() {
        Session session = null;
        List retval = null;
        String query = "KickstartCommandName.requiredOptions";
        session = HibernateFactory.getSession();
        retval = session.getNamedQuery(query)
        //Retrieve from cache if there
        .setCacheable(true).list();            
        return retval;
    }
    
    /**
     * Insert or Update a CryptoKey.
     * @param cryptoKeyIn CryptoKey to be stored in database.
     */
    public static void saveCryptoKey(CryptoKey cryptoKeyIn) {
        singleton.saveObject(cryptoKeyIn);
    }
 
    /**
     * remove a CryptoKey from the DB.
     * @param cryptoKeyIn CryptoKey to be removed from the database.
     */
    public static void removeCryptoKey(CryptoKey cryptoKeyIn) {
        singleton.removeObject(cryptoKeyIn);
    }
    
    /**
     * Insert or Update a Command.
     * @param commandIn Command to be stored in database.
     */
    public static void saveCommand(KickstartCommand commandIn) {
        singleton.saveObject(commandIn);
    }
   
    /**
     * 
     * @param ksdataIn Kickstart Data to be stored in db
     */
    public static void saveKickstartData(KickstartData ksdataIn) {
        singleton.saveObject(ksdataIn);
    }
    
    /**
     * @param ksdataIn Kickstart Data to be removed from the db
     * @return number of tuples affected by delete
     */
    public static int removeKickstartData(KickstartData ksdataIn) {
        return singleton.removeObject(ksdataIn);
    }

    /**
     * Lookup a crypto key by its description and org.
     * @param description to check
     * @param org to lookup in
     * @return CryptoKey if found.
     */
    public static CryptoKey lookupCryptoKey(String description, Org org) {
        Session session = null;
        CryptoKey retval = null;
        session = HibernateFactory.getSession();
        retval = (CryptoKey) session.getNamedQuery("CryptoKey.findByDescAndOrg")
                                      .setString("description", description)
                                      .setLong("org_id", org.getId().longValue())
                                      .uniqueResult();
        return retval;
    }
    
    /**
     * Find all crypto keys for a given org
     * @param org owning org
     * @return list of crypto keys if some found, else empty list
     */
    public static List<CryptoKey> lookupCryptoKeys(Org org) {
        Session session = null;
        List<CryptoKey> retval = null;
        //look for Kickstart data by id
        session = HibernateFactory.getSession();
        retval = session.getNamedQuery("CryptoKey.findByOrg")
                                      .setLong("org_id", org.getId().longValue())
                                      .list();
        return retval;        
    }

    /**
     * Lookup a crypto key by its id.
     * @param keyId to lookup
     * @param org who owns the key
     * @return CryptoKey if found.  Null if not
     */
    public static CryptoKey lookupCryptoKeyById(Long keyId, Org org) {
        Session session = null;
        CryptoKey retval = null;
        //look for Kickstart data by id
        session = HibernateFactory.getSession();
        retval = (CryptoKey) session.getNamedQuery("CryptoKey.findByIdAndOrg")
                                      .setLong("key_id", keyId.longValue())
                                      .setLong("org_id", org.getId().longValue())
                                      .uniqueResult();
        return retval;
    }
    
    /**
     * 
     * @param org who owns the Kickstart Range
     * @return List of Kickstart Ip Ranges if found
     */
    public static List lookupRangeByOrg(Org org) {
        Session session = null;
        session = HibernateFactory.getSession();
        return session.getNamedQuery("KickstartIpRange.lookupByOrg")
                      .setEntity("org", org)                          
                      .list();
    }

    /**
     * Lookup a KickstartableTree by its label.  If the Tree isnt owned
     * by the Org it will lookup a BaseChannel with a NULL Org under
     * the same label.
     * 
     * @param label to lookup
     * @param org who owns the Tree.  If none found will lookup RHN owned Trees
     * @return KickstartableTree if found.
     */
    public static KickstartableTree lookupKickstartTreeByLabel(String label, Org org) {
        Session session = null;
        KickstartableTree retval = null;
        session = HibernateFactory.getSession();
        retval = (KickstartableTree)
            session.getNamedQuery("KickstartableTree.findByLabelAndOrg")
                                      .setString("label", label)
                                      .setLong("org_id", org.getId().longValue())
                                      .uniqueResult();
        // If we don't find by label + org then
        // we try by label and NULL org (RHN owned channel)
        if (retval == null) {
            retval = (KickstartableTree) 
                session.getNamedQuery("KickstartableTree.findByLabelAndNullOrg")
            .setString("label", label)
            .uniqueResult();
        }
        return retval;
    }

    /**
     * Lookup a list of KickstartableTree objects that use the passed in channelId
     * 
     * @param channelId that owns the kickstart trees
     * @param org who owns the trees
     * @return List of KickstartableTree objects
     */
    public static List<KickstartableTree> lookupKickstartTreesByChannelAndOrg(
            Long channelId, Org org) {
        
        Session session = null;
        List retval = null;
        String query = "KickstartableTree.findByChannelAndOrg";
        session = HibernateFactory.getSession();
        retval = session.getNamedQuery(query).
        setLong("channel_id", channelId.longValue()).
        setLong("org_id", org.getId().longValue())
        //Retrieve from cache if there
        .setCacheable(true).list();            
        return retval;
    }
    
    /**
     * Lookup a list of KickstartableTree objects that use the passed in channelId
     * 
     * @param channelId that owns the kickstart trees
     * @param org who owns the trees
     * @return List of KickstartableTree objects
     */
    public static List<KickstartableTree> lookupKickstartableTrees(
            Long channelId, Org org) {
        
        Session session = null;
        List retval = null;
        String query = null;
        query = "KickstartableTree.findByChannel";
        try {
            session = HibernateFactory.getSession();
            retval = session.getNamedQuery(query).
            setLong("channel_id", channelId.longValue()).
            setLong("org_id", org.getId().longValue()).
            list();            
        }
        catch (HibernateException e) {
            log.error(e);
            throw new
                HibernateRuntimeException("Error looking up static kickstart " +
                        "command names adv options list");
        }
        return retval;
    }    
    
    /**
     * Fetch all trees for an org
     * @param org owning org
     * @return list of KickstartableTrees
     */
    public static List lookupKickstartTreesByOrg(Org org) {
        Session session = null;
        List retval = null;
        String query = "KickstartableTree.findByOrg";
        try {
            session = HibernateFactory.getSession();
            retval = session.getNamedQuery(query).
            setLong("org_id", org.getId().longValue())
            //Retrieve from cache if there
            .setCacheable(true).list();            
        }
        catch (HibernateException e) {
            e.printStackTrace();
            log.error(e);
            throw new
                HibernateRuntimeException("Error looking up static kickstart " +
                        "command names adv options list");
        }
        return retval;        
    }
    
    /**
     * Lookup KickstartableTree by tree id and org id
     * @param treeId desired tree
     * @param org owning org
     * @return KickstartableTree if found, otherwise null
     */
    public static KickstartableTree lookupKickstartTreeByIdAndOrg(Long treeId, Org org) {
        Session session = null;
        KickstartableTree retval = null;
        String queryName = "KickstartableTree.findByIdAndOrg";
        if (treeId != null && org != null) {
            try {
                session = HibernateFactory.getSession();
                Query query = session.getNamedQuery(queryName);
                query.setLong("org_id", org.getId().longValue());
                query.setLong("tree_id", treeId.longValue());
                //Retrieve from cache if there
                retval = (KickstartableTree)
                    query.setCacheable(true).uniqueResult();
                
            }
            catch (HibernateException e) {
                log.error(e);
                throw new
                    HibernateRuntimeException("Error looking up KickstartableTree. ", e);
            }
        }
        return retval;                
    }
    
    /**
     * Lookup a KickstartSession for a the passed in Server.  This method
     * finds the *most recent* KickstartSession associated with this Server.
     * 
     * We use the serverId instead of the Hibernate object because this method gets
     * called by our ACL layer.
     * 
     * @param sidIn id of the Server that you want to lookup the most 
     * recent KickstartSession for
     * @return KickstartSession if found.
     */
    public static KickstartSession lookupKickstartSessionByServer(Long sidIn) {
        Session session = null;
        try {
            session = HibernateFactory.getSession();
            List ksessions = session.getNamedQuery("KickstartSession.findByServer")
                          .setLong("server", sidIn.longValue())
                          .list();
            if (ksessions.size() > 0) {
                return (KickstartSession) ksessions.iterator().next();
            }
            else {
                return null;
            }
        }
        catch (HibernateException he) {
            log.error("Hibernate exception: " + he.toString());
        }
        return null;
        

    }
    
    /**
     * Helper method to lookup KickstartSessionState by label
     * @param label Label to lookup
     * @return Returns the KickstartSessionState
     * @throws Exception
     */
    public static KickstartSessionState lookupSessionStateByLabel(String label) {
        Session session = HibernateFactory.getSession();
        KickstartSessionState retval = (KickstartSessionState) session
            .getNamedQuery("KickstartSessionState.findByLabel")
            .setString("label", label)
            .uniqueResult();
        return retval; 
    }

    /**
     * Save a KickstartSession object
     * @param ksession to save.
     */
    public static void saveKickstartSession(KickstartSession ksession) {
        singleton.saveObject(ksession);
    }

    /**
     * Get all the KickstartSessions associated with the passed in server id
     * @param sidIn of Server we want the Sessions for
     * @return List of KickstartSession objects
     */
    public static List lookupAllKickstartSessionsByServer(Long sidIn) {
        Session session = null;
        try {
            session = HibernateFactory.getSession();
            return session.getNamedQuery("KickstartSession.findByServer")
                          .setLong("server", sidIn.longValue())
                          .list();
        }
        catch (HibernateException he) {
            log.error("Hibernate exception: " + he.toString());
        }
        return null;
    }

    /**
     * Lookup a KickstartSession by its id. 
     * @param sessionId to lookup
     * @return KickstartSession if found.
     */
    public static KickstartSession lookupKickstartSessionById(Long sessionId) {
        Session session = null;
        try {
            session = HibernateFactory.getSession();
            KickstartSession a = (KickstartSession) 
                session.get(KickstartSession.class, sessionId);
            return a;
        }
        catch (HibernateException he) {
            log.error("Hibernate exception: " + he.toString());
            throw new HibernateRuntimeException(
                "HibernateException while trying to lookup Action", he);
        }
        
    }
    private static KickstartTreeType lookupKickstartTreeTypeByLabel(String label) {
        Session session = HibernateFactory.getSession();
        KickstartTreeType retval = (KickstartTreeType) session
            .getNamedQuery("KickstartTreeType.findByLabel")
            .setString("label", label)
            .uniqueResult();
        return retval; 
    }
    
    /**
     * Verfies that a given kickstart tree can be used based on a channel id
     * and org id
     * @param channelId base channel
     * @param orgId org
     * @param treeId kickstart tree
     * @return true if it can, false otherwise
     */
    public static boolean verifyTreeAssignment(Long channelId, Long orgId, Long treeId) {
        Session session = null;
        boolean retval = false;
        if (channelId != null && orgId != null && treeId != null) {
            try {
                session = HibernateFactory.getSession();
                Query query = session.
                    getNamedQuery("KickstartableTree.verifyTreeAssignment");
                query.setLong("channel_id", channelId.longValue());
                query.setLong("org_id", orgId.longValue());
                query.setLong("tree_id", treeId.longValue());
                Object tree = query.uniqueResult();
                retval = (tree != null);
            }
            catch (HibernateException he) {
                log.error("Hibernate exception: " + he.toString());
                throw new HibernateRuntimeException(
                    "HibernateException while trying to lookup Action", he);
            }
        }
        return retval;
    }

    /**
     * Load a tree based on its id and org id
     * @param treeId kickstart tree id
     * @param orgId org id
     * @return KickstartableTree instance if found, otherwise null
     */
    public static KickstartableTree findTreeById(Long treeId, Long orgId) {
        KickstartableTree retval = null;
        retval = (KickstartableTree) 
            HibernateFactory.getSession().load(KickstartableTree.class, treeId);
        if (retval != null) {
            if (retval.getChannel().getOrg() != null && 
                    !retval.getChannel().getOrg().getId().equals(orgId)) {
                retval = null;
            }
        }
        return retval;
    }

    /**
     * Lookup a KickstartInstallType by label
     * @param label to lookup by
     * @return KickstartInstallType if found
     */
    public static KickstartInstallType lookupKickstartInstallTypeByLabel(String label) {
        Session session = HibernateFactory.getSession();
        KickstartInstallType retval = (KickstartInstallType) session
            .getNamedQuery("KickstartInstallType.findByLabel")
            .setString("label", label)
            .uniqueResult();
        return retval; 
    }
    
    /**
     * Return a List of KickstartInstallType classes.
     * @return List of KickstartInstallType instances
     */
    public static List lookupKickstartInstallTypes() {
        Session session = null;
        List retval = null;
        String query = "KickstartInstallType.loadAll";
        session = HibernateFactory.getSession();
        
        //Retrieve from cache if there
        retval = session.getNamedQuery(query).setCacheable(true).list();
        
        return retval;
    }

    /**
     * Return the guest install log as an ordered list of String objects.  This
     * method requires a kickstart session ID as input.
     * @param ksSessionId The id of the kickstart session to lookup.
     * @return The guest install log as an ordered list of String objects.
     */
    public static List lookupGuestKickstartInstallLog(Long ksSessionId) {
        Session session = HibernateFactory.getSession();
        List result = 
            session.getNamedQuery(
                "KickstartGuestInstallLog.findLogMessagesBySessionId")
                   .setLong("sessionId", ksSessionId.longValue())
                   .list();
        return result;
    }

    /**
     * Returns the latest guest install log entry.  This method requires a
     * kickstart session ID as input.
     * @param ksSessionId The id of the kickstart session to lookup.
     * @return The latest guest install log entry for the given ks session id.
     */
    public static KickstartGuestInstallLog lookupLatestGuestKickstartInstallLog(
        Long ksSessionId) {

        Session session = HibernateFactory.getSession();
        KickstartGuestInstallLog result = (KickstartGuestInstallLog) 
            session.getNamedQuery(
                "KickstartGuestInstallLog.findNewestLogEntriesBySessionId")
                   .setLong("sessionId", ksSessionId.longValue())
                   .setMaxResults(1)
                   .uniqueResult();

        return result;
    }

    /**
     * Save the KickstartableTree to the DB.
     * @param tree to save
     */
    public static void saveKickstartableTree(KickstartableTree tree) {
        singleton.saveObject(tree);
    }
    
    /**
     * Remove KickstartableTree from the DB.
     * @param tree to delete
     */
    public static void removeKickstartableTree(KickstartableTree tree) {
        singleton.removeObject(tree);
    }

    /**
     * Lookup a list of KickstartData objects by the KickstartableTree.
     * 
     * Useful for finding KickstartData objects that are using a specified Tree.
     * 
     * @param tree to lookup by
     * @return List of KickstartData objects if found
     */
    public static List lookupKickstartDatasByTree(KickstartableTree tree) {
        String query = "KickstartData.lookupByTreeId";
        Session session = HibernateFactory.getSession();
        return session.getNamedQuery(query)
            .setLong("kstree_id", tree.getId().longValue())
            .list();
    }

    
    /**
     * Lookup a KickstartData that has its isOrgDefault value set to true
     * This may return null if there aren't any set.
     * 
     * @param org who owns the Kickstart.
     * @return KickstartData if found
     */
    public static KickstartData lookupOrgDefault(Org org) {
        Session session = HibernateFactory.getSession();
        
        return (KickstartData) session
            .getNamedQuery("KickstartData.findOrgDefault")
            .setEntity("org", org)
            .setString("isOrgDefault", "Y")
            .uniqueResult();
    }
    
    /**
     * Fetch all virtualization types
     * @return list of VirtualizationTypes
     */
    public static List lookupVirtualizationTypes() {
        Session session = null;
        List retval = null;
        String query = "KickstartVirtualizationType.findAll";
        try {
            session = HibernateFactory.getSession();
            retval = session.getNamedQuery(query)
                .setCacheable(true).list();            
        }
        catch (HibernateException e) {
            e.printStackTrace();
            log.error(e);
            throw new
                HibernateRuntimeException("Error looking up virtualization types");
        }
        return retval;
    }

    /**
     * Lookup a KickstartVirtualizationType by label
     * @param label to lookup by
     * @return KickstartVirtualizationType if found
     */
    public static KickstartVirtualizationType 
        lookupKickstartVirtualizationTypeByLabel(String label) {
        Session session = HibernateFactory.getSession();
        KickstartVirtualizationType retval = (KickstartVirtualizationType) session
            .getNamedQuery("KickstartVirtualizationType.findByLabel")
            .setString("label", label)
            .uniqueResult();
        return retval; 
    }    
    
    /**
     * Fail the kickstart sessions associated with the given actions and servers.
     * 
     * @param actionsToDelete Actions associated with the kickstart sessions to fail.
     * @param servers Servers assocaited with the kickstart sessions to fail.
     */
    public static void failKickstartSessions(Set actionsToDelete, Set servers) {
        Session session = HibernateFactory.getSession();
        Iterator iter;
        KickstartSessionState failed = KickstartFactory.SESSION_STATE_FAILED;
        Query kickstartSessionQuery = session.getNamedQuery(
            "KickstartSession.findPendingForActions");
        kickstartSessionQuery.setParameterList("servers", servers);
        kickstartSessionQuery.setParameterList("actions_to_delete", actionsToDelete);

        List ksSessions = kickstartSessionQuery.list();
        iter = ksSessions.iterator();
        while (iter.hasNext()) {
            KickstartSession ks = (KickstartSession)iter.next();
            log.debug("Failing kickstart associated with action: " + ks.getId());
            ks.setState(failed);
            ks.setAction(null);
            
            setKickstartSessionHistoryMessage(ks, failed, KICKSTART_CANCELLED_MESSAGE);
        }
    }
    
    /**
     * Set the kickstart session history message.
     * 
     * Java version of the stored procedure set_ks_session_history_message. This procedure
     * attempted to iterate all states with the given label, but these are unique and
     * this method will not attempt to do the same.
     * 
     * @param ksSession
     * @param stateLabel
     */
    // TODO: Find a better location for this method.
    private static void setKickstartSessionHistoryMessage(KickstartSession ksSession, 
            KickstartSessionState state, String message) {
        Session session = HibernateFactory.getSession();
        Query q = session.getNamedQuery(
                "KickstartSessionHistory.findByKickstartSessionAndState");
        q.setEntity("state", state);
        q.setEntity("kickstartSession", ksSession);
        List results = q.list();
        Iterator iter = results.iterator();
        while (iter.hasNext()) {
            KickstartSessionHistory history = (KickstartSessionHistory)iter.next();
            history.setMessage(message);
        }

        ksSession.addHistory(state, message);
    }

    /**
     * Gets a kickstart script
     * @param org the org doing the request
     * @param id  the id of the script
     * @return the kickstartScript
     */
    public static KickstartScript lookupKickstartScript(Org org, Integer id) {
        KickstartScript script = (KickstartScript) HibernateFactory.getSession().load(
                KickstartScript.class, id.longValue());
        if (!org.equals(script.getKsdata().getOrg())) {
            return null;
        }
        return script;
    }
    
    /**
     * Completely remove a kickstart script from the system 
     * @param script the script to remove
     */
    public static void removeKickstartScript(KickstartScript script) {
        singleton.removeObject(script);
    }
    
}
