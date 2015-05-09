﻿using System;
using System.Collections.Generic;
using System.Configuration;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using US.OpenServer.Configuration;
using US.OpenServer.Protocols;

namespace US.OpenServer
{
    /// <summary>
    /// Class that connects to the server and optionally enables SSL/TLS 1.2.
    /// </summary>
    public class Client
    {
        #region Events
        /// <summary>
        /// Delegate that defines the event callback for the <see cref="OnConnectionLost"/> event.
        /// </summary>
        /// <param name="sender">The source of the event.</param>
        /// <param name="ex">An Exception that contains the reason the connection was lost.</param>
        public delegate void OnConnectionLostHandler(object sender, Exception ex);

        /// <summary>
        /// Event that is triggered when the connection to the server is lost.
        /// </summary>
        public event OnConnectionLostHandler OnConnectionLost;
        #endregion

        #region Variables
        /// <summary>
        /// A reference to the logger.
        /// </summary>
        private ILogger logger;

        /// <summary>
        /// A reference to the server configuration.
        /// </summary>
        private ServerConfiguration cfg;

        /// <summary>
        /// A reference to the connection session.
        /// </summary>
        private Session session;
        #endregion

        #region Constructor
        /// <summary> Creates an instance of Client. </summary>
        /// <remarks> All parameters are optional. If null is passed, the object's
        /// configuration is read from the app.config file. </remarks>
        /// <param name="logger">An optional ILogger to log messages. If null is passed,
        /// a <see cref="US.OpenServer.Logger"/> object is created.</param>
        /// <param name="cfg">An optional ServerConfiguration that contains the
        /// properties necessary to connect to the server. If null is passed, the
        /// configuration is read from the app.config's 'server' XML section
        /// node.</param>
        /// <param name="protocolConfigurations">An optional Dictionary of
        /// ProtocolConfiguration objects keyed with each protocol's unique identifier.
        /// If null is passed, the configuration is read from the app.config's
        /// 'protocols' XML section node.</param>
        public Client(
            ILogger logger = null,
            ServerConfiguration cfg = null,
            Dictionary<ushort, ProtocolConfiguration> protocolConfigurations = null)
        {
            if (logger == null)
                logger = new Logger("DotNetOpenClient");
            this.logger = logger;

            if (cfg == null)
                cfg = (ServerConfiguration)ConfigurationManager.GetSection("server");
            this.cfg = cfg;

            if (protocolConfigurations == null)
                protocolConfigurations = (Dictionary<ushort, ProtocolConfiguration>)ConfigurationManager.GetSection("protocols");
            ProtocolConfigurations.Items = protocolConfigurations;
        }
        #endregion

        #region Public Functions
        /// <summary>
        /// Connects to the server, creates a Session, optionally enables SSL/TLS 1.2
        /// and begins an asynchronous socket read operation.
        /// </summary>
        public void Connect()
        {
            Close();

            Socket server = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
            server.ReceiveTimeout = cfg.ReceiveTimeoutInMS;
            server.SendTimeout = cfg.SendTimeoutInMS;
            server.LingerState = new LingerOption(true, 10);
            server.NoDelay = true;
            if (string.IsNullOrEmpty(cfg.Host))
                cfg.Host = ServerConfiguration.DEFAULT_HOST;
            server.Connect(cfg.Host, cfg.Port);
            string address = ((IPEndPoint)server.RemoteEndPoint).Address.ToString();
            
            session = new Session(new NetworkStream(server), address, cfg.TlsConfiguration, logger);
            session.OnConnectionLost += session_OnConnectionLost;

            if (cfg.TlsConfiguration != null && cfg.TlsConfiguration.Enabled)
                EnableTls();

            session.Log(Level.Info, string.Format("Connected to {0}:{1}.", cfg.Host, cfg.Port));

            session.BeginRead();
        }

        /// <summary>
        /// A function that wraps the <see cref="US.OpenServer.SessionBase.Initialize(ushort, object = null)"/>
        /// function which creates then initializes the protocol.
        /// </summary>
        /// <param name="protocolId">A UInt16 that specifies the unique protocol
        /// identifier.</param>
        /// <param name="userData">An object that may be used client applications to pass
        /// objects or data to client side protocol implementations.</param>
        /// <returns>An IProtocol that implements the protocol layer.</returns>
        public IProtocol Initialize(ushort protocolId, object userData = null)
        {
             return session != null ? session.Initialize(protocolId, userData) : null;
        }

        /// <summary>
        /// Closes the protocol.
        /// </summary>
        /// <param name="protocolId">A UInt16 that specifies the unique protocol
        /// identifier.</param>
        public void Close(ushort protocolId)
        {
            if (session != null)
            {
                session.Close(protocolId);
            }
        }

        /// <summary>
        /// Closes the <see cref="Session"/>.
        /// </summary>
        public void Close()
        {
            if (session != null)
            {
                session.Close();
                session = null;
            }
        }
        #endregion

        #region Private Functions
        /// <summary>
        /// Event handler for <see cref="SessionBase.OnConnectionLost"/> events.
        /// </summary>
        /// <remarks> When a connection is lost, the Exception is forwarded to objects
        /// that have subscribed to <see cref="OnConnectionLost"/> events.</remarks>
        /// <param name="sender">An object that contains state information for this validation.</param>
        /// <param name="ex">An Exception that contains the error the connection was lost.</param>
        private void session_OnConnectionLost(object sender, Exception ex)
        {
            if (OnConnectionLost != null)
                OnConnectionLost(this, ex);
        }
        #endregion

        #region TLS
        /// <summary>
        /// Enables SSL/TLS 1.2.
        /// </summary>
        /// <remarks> Registers the <see cref="Session.TlsCertificateValidationCallback"/>
        /// and <see cref="Session.TlsCertificateSelectionCallback"/> with the
        /// SslStream, optionally gets a client side SSL certificate from the local
        /// certificate store, then authenticates the connection. </remarks>
        private void EnableTls()
        {
            RemoteCertificateValidationCallback validationCallback =
              new RemoteCertificateValidationCallback(session.TlsCertificateValidationCallback);

            LocalCertificateSelectionCallback selectionCallback =
              new LocalCertificateSelectionCallback(session.TlsCertificateSelectionCallback);

            SslStream sslStream = new SslStream(session.Stream, true, validationCallback, selectionCallback, EncryptionPolicy.RequireEncryption);
            session.Stream = sslStream;

            X509Certificate2 certificate = session.GetCertificateFromStore(string.Format("CN={0}", cfg.TlsConfiguration.Certificate));
            X509CertificateCollection certificates = new X509CertificateCollection();
            if (certificate != null)
                certificates.Add(certificate);

            ((SslStream)session.Stream).AuthenticateAsClient(
                cfg.Host, certificates, SslProtocols.Tls, cfg.TlsConfiguration.CheckCertificateRevocation);
        }
        #endregion
    }
}