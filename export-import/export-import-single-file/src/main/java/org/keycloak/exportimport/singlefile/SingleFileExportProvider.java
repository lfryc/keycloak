package org.keycloak.exportimport.singlefile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.exportimport.ExportProvider;
import org.keycloak.exportimport.util.ExportImportJob;
import org.keycloak.exportimport.util.ExportImportUtils;
import org.keycloak.exportimport.util.ExportUtils;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.util.JsonSerialization;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class SingleFileExportProvider implements ExportProvider {

    private static final Logger logger = Logger.getLogger(SingleFileExportProvider.class);

    private File file;

    public SingleFileExportProvider(File file) {
        this.file = file;
    }

    public void setFile(File file) {
        this.file = file;
    }

    @Override
    public void exportModel(KeycloakSessionFactory factory) throws IOException {
        logger.infof("Exporting model into file %s", this.file.getAbsolutePath());
        ExportImportUtils.runJobInTransaction(factory, new ExportImportJob() {

            @Override
            public void run(KeycloakSession session) throws IOException {
                List<RealmModel> realms = session.realms().getRealms();
                List<RealmRepresentation> reps = new ArrayList<RealmRepresentation>();
                for (RealmModel realm : realms) {
                    reps.add(ExportUtils.exportRealm(session, realm, true));
                }

                writeToFile(reps);
            }

        });

    }

    @Override
    public void exportRealm(KeycloakSessionFactory factory, final String realmName) throws IOException {
        logger.infof("Exporting realm '%s' into file %s", realmName, this.file.getAbsolutePath());
        ExportImportUtils.runJobInTransaction(factory, new ExportImportJob() {

            @Override
            public void run(KeycloakSession session) throws IOException {
                RealmModel realm = session.realms().getRealmByName(realmName);
                RealmRepresentation realmRep = ExportUtils.exportRealm(session, realm, true);
                writeToFile(realmRep);
            }

        });
    }

    @Override
    public void close() {
    }

    private ObjectMapper getObjectMapper() {
        return JsonSerialization.prettyMapper;
    }

    private void writeToFile(Object reps) throws IOException {
        FileOutputStream stream = new FileOutputStream(this.file);
        getObjectMapper().writeValue(stream, reps);
    }
}
