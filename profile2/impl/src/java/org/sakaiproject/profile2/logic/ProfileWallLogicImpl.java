/**
 * Copyright (c) 2008-2010 The Sakai Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sakaiproject.profile2.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.sakaiproject.profile2.dao.ProfileDao;
import org.sakaiproject.profile2.model.Person;
import org.sakaiproject.profile2.model.ProfilePrivacy;
import org.sakaiproject.profile2.model.WallItem;
import org.sakaiproject.profile2.util.ProfileConstants;

/**
 * Implementation of ProfileWallLogic API for Profile2 wall.
 * 
 * @author d.b.robinson@lancaster.ac.uk
 */
public class ProfileWallLogicImpl implements ProfileWallLogic {
	
	private static final Logger log = Logger.getLogger(ProfileWallLogic.class);
		
	/**
	 * Creates a new instance of <code>ProfileWallLogicImpl</code>.
	 */
	public ProfileWallLogicImpl() {
		
	}
	
	private void addItemToWalls(int itemType, String itemText, final String userUuid) {
		
		// get the connections of the creator of this content
		final List<Person> connections = connectionsLogic.getConnectionsForUser(userUuid);

		if (null == connections || 0 == connections.size()) {
			// there are therefore no walls to post event to
			return;
		}

		// set corresponding message type and exit if type unknown
		final int itemMessageType;
		switch (itemType) {
			case ProfileConstants.WALL_ITEM_TYPE_EVENT:
				itemMessageType = ProfileConstants.EMAIL_NOTIFICATION_WALL_EVENT_NEW;
				break;
			case ProfileConstants.WALL_ITEM_TYPE_STATUS:
				itemMessageType = ProfileConstants.EMAIL_NOTIFICATION_WALL_STATUS_NEW;
				break;
			default:
				log.warn("not sending email due to unknown wall item type: " + itemType);
				return;
		}
		
		final WallItem wallItem = new WallItem();

		wallItem.setCreatorUuid(userUuid);
		wallItem.setType(itemType);
		wallItem.setDate(new Date());
		// this string is mapped to a localized resource string in GUI
		wallItem.setText(itemText);

		Thread thread = new Thread() {
			public void run() {
				
				List<String> uuidsToEmail = new ArrayList<String>();
				
				for (Person connection : connections) {
		
					// only send email if successful
					if (dao.addNewWallItemForUser(connection.getUuid(), wallItem)) {
		
						// only send email if user has preference set
						if (true == preferencesLogic.isEmailEnabledForThisMessageType(
								connection.getUuid(), itemMessageType)) {
							
							uuidsToEmail.add(connection.getUuid());
						}
						
					} else {
						// we don't guarantee delivery
						log.warn("ProfileDao.addNewWallItemForUser failed for user: " + connection.getUuid());
					}
				}
				
				sendWallNotificationEmailToConnections(uuidsToEmail, userUuid, itemMessageType);
			}
		};
		thread.start();
	}
	
	/**
	 * {@inheritDoc}
	 */
	public void addEventToWalls(String event, final String userUuid) {
		addItemToWalls(ProfileConstants.WALL_ITEM_TYPE_EVENT, event, userUuid);
	}
	
	/**
	 * {@inheritDoc}
	 */
	public void addStatusToWalls(String status, String userUuid) {
		addItemToWalls(ProfileConstants.WALL_ITEM_TYPE_STATUS, status, userUuid);
	}
	
	/**
	 * {@inheritDoc}
	 */
	public boolean postWallItemToWall(final String userUuid, final WallItem wallItem) {
		// post to wall
		if (false == dao.addNewWallItemForUser(userUuid, wallItem)) {
			return false;
		}

		// don't email user if they've posted on their own wall
		if (false == sakaiProxy.getCurrentUserId().equals(userUuid)) {
			sendWallNotificationEmailToUser(userUuid, wallItem.getCreatorUuid(),
					ProfileConstants.EMAIL_NOTIFICATION_WALL_POST_MY_NEW);
		}
		// and if they have posted on their own wall, let connections know
		else {
			// get the connections of the user associated with the wall
			final List<Person> connections = connectionsLogic.getConnectionsForUser(userUuid);

			if (null != connections) {

				Thread thread = new Thread() {
					public void run() {
						
						List<String> uuidsToEmail = new ArrayList<String>();
						
						for (Person connection : connections) {
		
							// only send email if successful
							if (true == dao.addNewWallItemForUser(connection.getUuid(),
									wallItem)) {
								
								// only send email if user has preference set
								if (true == preferencesLogic.isEmailEnabledForThisMessageType(connection.getUuid(),
										ProfileConstants.EMAIL_NOTIFICATION_WALL_POST_CONNECTION_NEW)) {
									uuidsToEmail.add(connection.getUuid());
								}
								
							} else {
								log.warn("ProfileDao.addNewWallItemForUser failed for user: " + connection.getUuid());
							}
						}
						
						sendWallNotificationEmailToConnections(uuidsToEmail, userUuid,
								ProfileConstants.EMAIL_NOTIFICATION_WALL_POST_CONNECTION_NEW);
					}
				};
				thread.start();
			}
		}

		return true;
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean removeWallItemFromWall(String userUuid, WallItem wallItem) {
		return dao.removeWallItemFromWall(userUuid, wallItem);
	}
	
	/**
 	 * {@inheritDoc}
 	 */
	public List<WallItem> getWallItemsForUser(String userUuid, ProfilePrivacy privacy) {

		if (null == userUuid) {
			throw new IllegalArgumentException("must provide user id");
		}

		final String currentUserUuid = sakaiProxy.getCurrentUserId();
		if (null == currentUserUuid) {
			throw new SecurityException(
					"You must be logged in to make a request for a user's wall items.");
		}

		if (null == privacy) {
			return new ArrayList<WallItem>();
		}

		if (false == StringUtils.equals(userUuid, currentUserUuid) && false == sakaiProxy.isSuperUser()) {
			if (false == privacyLogic.isUserXWallVisibleByUserY(userUuid,
					privacy, currentUserUuid, connectionsLogic
							.isUserXFriendOfUserY(userUuid, currentUserUuid))) {
				return new ArrayList<WallItem>();
			}
		}

		List<WallItem> wallItems = dao.getWallItemsForUser(userUuid).getWallItems();
		
		// filter wall items
		List<WallItem> filteredWallItems = new ArrayList<WallItem>();
		for (WallItem wallItem : wallItems) {
			// current user is always allowed to see their wall items
			if (true == StringUtils.equals(userUuid, currentUserUuid) || 
					true == sakaiProxy.isSuperUser()) {
				filteredWallItems.add(wallItem);
			// don't allow friend-of-a-friend if not connected
			} else if (privacyLogic.isUserXWallVisibleByUserY(wallItem.getCreatorUuid(), currentUserUuid,
					connectionsLogic.isUserXFriendOfUserY(wallItem.getCreatorUuid(), currentUserUuid))) {
				filteredWallItems.add(wallItem);
			}
		}
		
		// wall items are comparable and need to be in order
		Collections.sort(filteredWallItems);
				
		return filteredWallItems;
	}
	
	/**
	 * {@inheritDoc}
	 */
	public List<WallItem> getWallItemsForUser(String userUuid) {
		return getWallItemsForUser(userUuid, privacyLogic
				.getPrivacyRecordForUser(userUuid));
	}
	
	/**
	 * {@inheritDoc}
	 */
	public int getWallItemsCount(String userUuid) {
		return getWallItemsCount(userUuid, privacyLogic
				.getPrivacyRecordForUser(userUuid));
	}
	
	/**
	 * {@inheritDoc}
	 */
	public int getWallItemsCount(String userUuid, ProfilePrivacy privacy) {

		final String currentUserUuid = sakaiProxy.getCurrentUserId();
		if (null == sakaiProxy.getCurrentUserId()) {
			throw new SecurityException(
					"You must be logged in to make a request for a user's wall items.");
		}

		if (null == privacy) {
			return 0;
		}

		if (false == StringUtils.equals(userUuid, currentUserUuid) && false == sakaiProxy.isSuperUser()) {

			if (false == privacyLogic.isUserXWallVisibleByUserY(userUuid,
					privacy, currentUserUuid, connectionsLogic
							.isUserXFriendOfUserY(userUuid, currentUserUuid))) {
				return 0;
			}
		}
				
		List<WallItem> wallItems = dao.getWallItemsForUser(userUuid).getWallItems();
		
		// filter wall items
		List<WallItem> filteredWallItems = new ArrayList<WallItem>();
		for (WallItem wallItem : wallItems) {
			// current user is always allowed to see their wall items
			if (true == StringUtils.equals(userUuid, currentUserUuid) || 
					true == sakaiProxy.isSuperUser()) {
				filteredWallItems.add(wallItem);
			// don't allow friend-of-a-friend if not connected
			} else if (privacyLogic.isUserXWallVisibleByUserY(wallItem.getCreatorUuid(), currentUserUuid,
					connectionsLogic.isUserXFriendOfUserY(wallItem.getCreatorUuid(), currentUserUuid))) {
				filteredWallItems.add(wallItem);
			}
		}
		
		return filteredWallItems.size();
	}
	
	private void sendWallNotificationEmailToConnections(List<String> toUuids, final String fromUuid, final int messageType) {
		
		// create the map of replacement values for this email template
		Map<String, String> replacementValues = new HashMap<String, String>();
		replacementValues.put("senderDisplayName", sakaiProxy.getUserDisplayName(fromUuid));
		replacementValues.put("senderProfileLink", linkLogic.getEntityLinkToProfileHome(fromUuid));
		replacementValues.put("localSakaiName", sakaiProxy.getServiceName());
		replacementValues.put("localSakaiUrl", sakaiProxy.getPortalUrl());
		replacementValues.put("toolName", sakaiProxy.getCurrentToolTitle());
		
		String emailTemplateKey = null;
		
		if (ProfileConstants.EMAIL_NOTIFICATION_WALL_EVENT_NEW == messageType) {
			emailTemplateKey = ProfileConstants.EMAIL_TEMPLATE_KEY_WALL_EVENT_NEW;			
		} else if (ProfileConstants.EMAIL_NOTIFICATION_WALL_POST_CONNECTION_NEW == messageType) {
			emailTemplateKey = ProfileConstants.EMAIL_TEMPLATE_KEY_WALL_POST_CONNECTION_NEW;
		} else if (ProfileConstants.EMAIL_NOTIFICATION_WALL_STATUS_NEW == messageType) {
			emailTemplateKey = ProfileConstants.EMAIL_TEMPLATE_KEY_WALL_STATUS_NEW;
		}
		
		if (null != emailTemplateKey) {
			// send individually to personalize email
			for (String toUuid : toUuids) {
				// this just keeps overwriting profileLink with current toUuid
				replacementValues.put("displayName", sakaiProxy.getUserDisplayName(toUuid));
				replacementValues.put("profileLink", linkLogic.getEntityLinkToProfileHome(toUuid));
				sakaiProxy.sendEmail(toUuid, emailTemplateKey, replacementValues);
			}
		} else {
			log.warn("not sending email, unknown message type for sendWallNotificationEmailToConnections: " + messageType);
		}
	}
	
	private void sendWallNotificationEmailToUser(String toUuid,
			final String fromUuid, final int messageType) {

		// check if email preference enabled
		if (!preferencesLogic.isEmailEnabledForThisMessageType(toUuid,
				messageType)) {
			return;
		}

		// create the map of replacement values for this email template
		Map<String, String> replacementValues = new HashMap<String, String>();
		replacementValues.put("senderDisplayName", sakaiProxy.getUserDisplayName(fromUuid));
		replacementValues.put("localSakaiName", sakaiProxy.getServiceName());
		replacementValues.put("localSakaiUrl", sakaiProxy.getPortalUrl());
		replacementValues.put("toolName", sakaiProxy.getCurrentToolTitle());
		
		String emailTemplateKey = null;
		
		if (ProfileConstants.EMAIL_NOTIFICATION_WALL_POST_MY_NEW == messageType) {
			emailTemplateKey = ProfileConstants.EMAIL_TEMPLATE_KEY_WALL_POST_MY_NEW;
			
			replacementValues.put("displayName", sakaiProxy.getUserDisplayName(toUuid));
			replacementValues.put("profileLink", linkLogic.getEntityLinkToProfileHome(toUuid));
		}
		
		if (null != emailTemplateKey) {
			sakaiProxy.sendEmail(toUuid, emailTemplateKey, replacementValues);
		} else {
			log.warn("not sending email, unknown message type for sendWallNotificationEmailToUser: " + messageType);
		}

	}
		
	// internal components
	private ProfileDao dao;
	public void setDao(ProfileDao dao) {
		this.dao = dao;
	}
	
	private ProfilePrivacyLogic privacyLogic;
	public void setPrivacyLogic(ProfilePrivacyLogic privacyLogic) {
		this.privacyLogic = privacyLogic;
	}
	
	private ProfileConnectionsLogic connectionsLogic;
	public void setConnectionsLogic(
			ProfileConnectionsLogic connectionsLogic) {
		this.connectionsLogic = connectionsLogic;
	}
	
	private ProfileLinkLogic linkLogic;
	public void setLinkLogic(ProfileLinkLogic linkLogic) {
		this.linkLogic = linkLogic;
	}
	
	private ProfilePreferencesLogic preferencesLogic;
	public void setPreferencesLogic(ProfilePreferencesLogic preferencesLogic) {
		this.preferencesLogic = preferencesLogic;
	}
	
	private SakaiProxy sakaiProxy;
	public void setSakaiProxy(SakaiProxy sakaiProxy) {
		this.sakaiProxy = sakaiProxy;
	}
		
}
