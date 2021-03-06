
package net.sourceforge.guacamole.net.auth.mysql.service;

/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is guacamole-auth-mysql.
 *
 * The Initial Developer of the Original Code is
 * James Muehlner.
 * Portions created by the Initial Developer are Copyright (C) 2010
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.sourceforge.guacamole.GuacamoleClientException;
import net.sourceforge.guacamole.GuacamoleException;
import net.sourceforge.guacamole.net.GuacamoleSocket;
import net.sourceforge.guacamole.net.InetGuacamoleSocket;
import net.sourceforge.guacamole.net.auth.mysql.ActiveConnectionSet;
import net.sourceforge.guacamole.net.auth.mysql.MySQLConnection;
import net.sourceforge.guacamole.net.auth.mysql.MySQLConnectionRecord;
import net.sourceforge.guacamole.net.auth.mysql.MySQLGuacamoleSocket;
import net.sourceforge.guacamole.net.auth.mysql.dao.ConnectionHistoryMapper;
import net.sourceforge.guacamole.net.auth.mysql.dao.ConnectionMapper;
import net.sourceforge.guacamole.net.auth.mysql.dao.ConnectionParameterMapper;
import net.sourceforge.guacamole.net.auth.mysql.model.Connection;
import net.sourceforge.guacamole.net.auth.mysql.model.ConnectionExample;
import net.sourceforge.guacamole.net.auth.mysql.model.ConnectionHistory;
import net.sourceforge.guacamole.net.auth.mysql.model.ConnectionHistoryExample;
import net.sourceforge.guacamole.net.auth.mysql.model.ConnectionParameter;
import net.sourceforge.guacamole.net.auth.mysql.model.ConnectionParameterExample;
import net.sourceforge.guacamole.net.auth.mysql.properties.MySQLGuacamoleProperties;
import net.sourceforge.guacamole.properties.GuacamoleProperties;
import net.sourceforge.guacamole.protocol.ConfiguredGuacamoleSocket;
import net.sourceforge.guacamole.protocol.GuacamoleClientInformation;
import net.sourceforge.guacamole.protocol.GuacamoleConfiguration;
import org.apache.ibatis.session.RowBounds;

/**
 * Service which provides convenience methods for creating, retrieving, and
 * manipulating connections.
 *
 * @author Michael Jumper, James Muehlner
 */
public class ConnectionService {

    /**
     * DAO for accessing connections.
     */
    @Inject
    private ConnectionMapper connectionDAO;

    /**
     * DAO for accessing connection parameters.
     */
    @Inject
    private ConnectionParameterMapper connectionParameterDAO;

    /**
     * DAO for accessing connection history.
     */
    @Inject
    private ConnectionHistoryMapper connectionHistoryDAO;

    /**
     * Provider which creates MySQLConnections.
     */
    @Inject
    private Provider<MySQLConnection> mySQLConnectionProvider;

    /**
     * Provider which creates MySQLGuacamoleSockets.
     */
    @Inject
    private Provider<MySQLGuacamoleSocket> mySQLGuacamoleSocketProvider;

    /**
     * Set of all currently active connections.
     */
    @Inject
    private ActiveConnectionSet activeConnectionSet;

    /**
     * Service managing users.
     */
    @Inject
    private UserService userService;

    /**
     * Retrieves the connection having the given name from the database.
     *
     * @param name The name of the connection to return.
     * @param userID The ID of the user who queried this connection.
     * @return The connection having the given name, or null if no such
     *         connection could be found.
     */
    public MySQLConnection retrieveConnection(String name, int userID) {

        // Query connection by connection identifier (name)
        ConnectionExample example = new ConnectionExample();
        example.createCriteria().andConnection_nameEqualTo(name);
        List<Connection> connections =
                connectionDAO.selectByExample(example);

        // If no connection found, return null
        if(connections.isEmpty())
            return null;

        // Assert only one connection found
        assert connections.size() == 1 : "Multiple connections with same name.";

        // Otherwise, return found connection
        return toMySQLConnection(connections.get(0), userID);

    }

    /**
     * Retrieves the connection having the given ID from the database.
     *
     * @param id The ID of the connection to retrieve.
     * @param userID The ID of the user who queried this connection.
     * @return The connection having the given ID, or null if no such
     *         connection was found.
     */
    public MySQLConnection retrieveConnection(int id, int userID) {

        // Query connection by ID
        Connection connection = connectionDAO.selectByPrimaryKey(id);

        // If no connection found, return null
        if(connection == null)
            return null;

        // Otherwise, return found connection
        return toMySQLConnection(connection, userID);
    }

    /**
     * Retrieves a translation map of connection names to their corresponding
     * IDs.
     *
     * @param ids The IDs of the connections to retrieve the names of.
     * @return A map containing the names of all connections and their
     *         corresponding IDs.
     */
    public Map<String, Integer> translateNames(List<Integer> ids) {

        // If no IDs given, just return empty map
        if (ids.isEmpty())
            return Collections.EMPTY_MAP;

        // Map of all names onto their corresponding IDs.
        Map<String, Integer> names = new HashMap<String, Integer>();

        // Get all connections having the given IDs
        ConnectionExample example = new ConnectionExample();
        example.createCriteria().andConnection_idIn(ids);
        List<Connection> connections = connectionDAO.selectByExample(example);

        // Produce set of names
        for (Connection connection : connections)
            names.put(connection.getConnection_name(),
                      connection.getConnection_id());

        return names;

    }

    /**
     * Retrieves a map of all connection names for the given IDs.
     *
     * @param ids The IDs of the connections to retrieve the names of.
     * @return A map containing the names of all connections and their
     *         corresponding IDs.
     */
    public Map<Integer, String> retrieveNames(Collection<Integer> ids) {

        // If no IDs given, just return empty map
        if (ids.isEmpty())
            return Collections.EMPTY_MAP;

        // Map of all names onto their corresponding IDs.
        Map<Integer, String> names = new HashMap<Integer, String>();

        // Get all connections having the given IDs
        ConnectionExample example = new ConnectionExample();
        example.createCriteria().andConnection_idIn(Lists.newArrayList(ids));
        List<Connection> connections = connectionDAO.selectByExample(example);

        // Produce set of names
        for (Connection connection : connections)
            names.put(connection.getConnection_id(),
                      connection.getConnection_name());

        return names;

    }

    /**
     * Convert the given database-retrieved Connection into a MySQLConnection.
     * The parameters of the given connection will be read and added to the
     * MySQLConnection in the process.
     *
     * @param connection The connection to convert.
     * @param userID The user who queried this connection.
     * @return A new MySQLConnection containing all data associated with the
     *         specified connection.
     */
    private MySQLConnection toMySQLConnection(Connection connection, int userID) {

        // Build configuration
        GuacamoleConfiguration config = new GuacamoleConfiguration();

        // Query parameters for configuration
        ConnectionParameterExample connectionParameterExample = new ConnectionParameterExample();
        connectionParameterExample.createCriteria().andConnection_idEqualTo(connection.getConnection_id());
        List<ConnectionParameter> connectionParameters =
                connectionParameterDAO.selectByExample(connectionParameterExample);

        // Set protocol
        config.setProtocol(connection.getProtocol());

        // Set all values for all parameters
        for (ConnectionParameter parameter : connectionParameters)
            config.setParameter(parameter.getParameter_name(),
                    parameter.getParameter_value());

        // Create new MySQLConnection from retrieved data
        MySQLConnection mySQLConnection = mySQLConnectionProvider.get();
        mySQLConnection.init(
            connection.getConnection_id(),
            connection.getConnection_name(),
            config,
            retrieveHistory(connection.getConnection_id()),
            userID
        );

        return mySQLConnection;

    }

    /**
     * Retrieves the history of the connection having the given ID.
     *
     * @param connectionID The ID of the connection to retrieve the history of.
     * @return A list of MySQLConnectionRecord documenting the history of this
     *         connection.
     */
    public List<MySQLConnectionRecord> retrieveHistory(int connectionID) {

        // Retrieve history records relating to given connection ID
        ConnectionHistoryExample example = new ConnectionHistoryExample();
        example.createCriteria().andConnection_idEqualTo(connectionID);

        // We want to return the newest records first
        example.setOrderByClause("start_date DESC");

        // Set the maximum number of history records returned to 100
        RowBounds rowBounds = new RowBounds(0, 100);

        // Retrieve all connection history entries
        List<ConnectionHistory> connectionHistories =
                connectionHistoryDAO.selectByExampleWithRowbounds(example, rowBounds);

        // Convert history entries to connection records
        List<MySQLConnectionRecord> connectionRecords = new ArrayList<MySQLConnectionRecord>();
        Set<Integer> userIDSet = new HashSet<Integer>();
        for(ConnectionHistory history : connectionHistories) {
            userIDSet.add(history.getUser_id());
        }

        // Get all the usernames for the users who are in the history
        Map<Integer, String> usernameMap = userService.retrieveUsernames(userIDSet);

        // Create the new ConnectionRecords
        for(ConnectionHistory history : connectionHistories) {
            Date startDate = history.getStart_date();
            Date endDate = history.getEnd_date();
            String username = usernameMap.get(history.getUser_id());
            MySQLConnectionRecord connectionRecord = new MySQLConnectionRecord(startDate, endDate, username);
            connectionRecords.add(connectionRecord);
        }

        return connectionRecords;
    }

    /**
     * Create a MySQLGuacamoleSocket using the provided connection.
     *
     * @param connection The connection to use when connecting the socket.
     * @param info The information to use when performing the connection
     *             handshake.
     * @param userID The ID of the user who is connecting to the socket.
     * @return The connected socket.
     * @throws GuacamoleException If an error occurs while connecting the
     *                            socket.
     */
    public MySQLGuacamoleSocket connect(MySQLConnection connection,
            GuacamoleClientInformation info, int userID)
        throws GuacamoleException {

        // If the given connection is active, and multiple simultaneous
        // connections are not allowed, disallow connection
        if(GuacamoleProperties.getProperty(
                MySQLGuacamoleProperties.MYSQL_DISALLOW_SIMULTANEOUS_CONNECTIONS, false)
                && activeConnectionSet.isActive(connection.getConnectionID()))
            throw new GuacamoleClientException("Cannot connect. This connection is in use.");

        // Get guacd connection information
        String host = GuacamoleProperties.getProperty(GuacamoleProperties.GUACD_HOSTNAME);
        int port = GuacamoleProperties.getProperty(GuacamoleProperties.GUACD_PORT);

        // Get socket
        GuacamoleSocket socket = new ConfiguredGuacamoleSocket(
            new InetGuacamoleSocket(host, port),
            connection.getConfiguration(), info
        );

        // Mark this connection as active
        int historyID = activeConnectionSet.openConnection(connection.getConnectionID(), userID);

        // Return new MySQLGuacamoleSocket
        MySQLGuacamoleSocket mySQLGuacamoleSocket = mySQLGuacamoleSocketProvider.get();
        mySQLGuacamoleSocket.init(socket, connection.getConnectionID(), historyID);
        return mySQLGuacamoleSocket;

    }

    /**
     * Creates a new connection having the given name and protocol.
     *
     * @param name The name to assign to the new connection.
     * @param protocol The protocol to assign to the new connection.
     * @param userID The ID of the user who created this connection.
     * @return A new MySQLConnection containing the data of the newly created
     *         connection.
     */
    public MySQLConnection createConnection(String name, String protocol, int userID) {

        // Initialize database connection
        Connection connection = new Connection();
        connection.setConnection_name(name);
        connection.setProtocol(protocol);

        // Create connection
        connectionDAO.insert(connection);
        return toMySQLConnection(connection, userID);

    }

    /**
     * Deletes the connection having the given ID from the database.
     * @param id The ID of the connection to delete.
     */
    public void deleteConnection(int id) {
        connectionDAO.deleteByPrimaryKey(id);
    }

    /**
     * Updates the connection in the database corresponding to the given
     * MySQLConnection.
     *
     * @param mySQLConnection The MySQLConnection to update (save) to the
     *                        database. This connection must already exist.
     */
    public void updateConnection(MySQLConnection mySQLConnection) {

        // Populate connection
        Connection connection = new Connection();
        connection.setConnection_id(mySQLConnection.getConnectionID());
        connection.setConnection_name(mySQLConnection.getIdentifier());
        connection.setProtocol(mySQLConnection.getConfiguration().getProtocol());

        // Update the connection in the database
        connectionDAO.updateByPrimaryKeySelective(connection);

    }

    /**
     * Get the names of all the connections defined in the system.
     *
     * @return A Set of names of all the connections defined in the system.
     */
    public Set<String> getAllConnectionNames() {

        // Set of all present connection names
        Set<String> names = new HashSet<String>();

        // Query all connection names
        List<Connection> connections =
                connectionDAO.selectByExample(new ConnectionExample());
        for (Connection connection : connections)
            names.add(connection.getConnection_name());

        return names;

    }

    /**
     * Get the connection IDs of all the connections defined in the system.
     *
     * @return A list of connection IDs of all the connections defined in the system.
     */
    public List<Integer> getAllConnectionIDs() {

        // Set of all present connection IDs
        List<Integer> connectionIDs = new ArrayList<Integer>();

        // Query all connection IDs
        List<Connection> connections =
                connectionDAO.selectByExample(new ConnectionExample());
        for (Connection connection : connections)
            connectionIDs.add(connection.getConnection_id());

        return connectionIDs;

    }

}
