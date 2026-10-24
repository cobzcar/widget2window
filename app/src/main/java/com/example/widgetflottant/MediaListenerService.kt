package com.example.widgetflottant

import android.service.notification.NotificationListenerService

/**
 * Service vide : sa seule utilité est d'être déclaré comme « accès aux notifications ».
 * Android n'autorise une appli à lire les lecteurs de musique en cours (titre, pochette, boutons)
 * que si elle a cet accès.
 */
class MediaListenerService : NotificationListenerService()
