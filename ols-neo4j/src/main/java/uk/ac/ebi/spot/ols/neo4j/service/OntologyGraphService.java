package uk.ac.ebi.spot.ols.neo4j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.ac.ebi.spot.ols.neo4j.model.Term;
import uk.ac.ebi.spot.ols.neo4j.repository.OntologyTermRepository;

import java.io.IOException;
import java.util.*;

/**
 * @author Simon Jupp
 * @date 23/06/2015
 * Samples, Phenotypes and Ontologies Team, EMBL-EBI
 */
@Service
public class OntologyGraphService {

    @Autowired(required = false)
    OntologyTermRepository termRepository;

    @Autowired(required = false)
    GraphDatabaseService graphDatabaseService;


    String parentGraphQuery =     "MATCH path = (n:Class)-[r:SUBCLASSOF|RelatedTree*]->(parent)\n"+
            "WHERE n.ontologyName = {0} AND n.iri = {1}\n"+
            "UNWIND nodes(path) as p\n" +
            "UNWIND rels(path) as r1\n" +
            "RETURN {nodes: collect( distinct {iri: p.iri, label: p.label}), edges: collect (distinct {source: startNode(r1).iri, target: endNode(r1).iri, label: r1.label, uri: r1.uri}  )} as result";

    String parentTreeQuery = "MATCH path = (n:Class)-[r:SUBCLASSOF*]->(parent)\n"+
            "USING INDEX n:Class(iri)\n" +
            "WHERE n.ontologyName = {0} AND n.iri = {1}\n"+
            "UNWIND rels(path) as r1\n" +
            "RETURN distinct id(startNode(r1)) as startId, startNode(r1).iri as startIri, startNode(r1).label as startLabel, startNode(r1).isLeafNode as isLeaf, collect( distinct id(endNode(r1)) ) as parents";

    String parentSiblingTreeQuery = "MATCH path = (n:Class)-[r:SUBCLASSOF*]->(parent)<-[r2:SUBCLASSOF]-(n1:Class)\n"+
            "USING INDEX n:Class(iri)\n" +
            "WHERE n.ontologyName = {0} AND n.iri = {1}\n"+
            "UNWIND rels(path) as r1\n" +
            "RETURN distinct id(startNode(r1)) as startId, startNode(r1).iri as startIri, startNode(r1).label as startLabel, startNode(r1).isLeafNode as isLeaf, collect( distinct id(endNode(r1)) ) as parents";

    @Transactional
    public Object getJsTree(String ontologyName, String iri) {
        Map<String, Object> paramt = new HashMap<>();
        paramt.put("0", ontologyName);
        paramt.put("1", iri);
        Result res = graphDatabaseService.execute(parentTreeQuery, paramt);

        Map<String, Map<String, Object>> resultsMap = new HashMap<>();
        while (res.hasNext()) {
            Map<String, Object> r = res.next();
            String nodeId = r.get("startId").toString();
            resultsMap.put(nodeId, r);
        }

        Map<String, Collection<JsTreeObject>> jsTreeObjectMap = new HashMap<>();
        Collection<String> parentIds = new HashSet<>();

        for (String id : resultsMap.keySet()) {
            generateJsTreeObject(id, ontologyName, jsTreeObjectMap, resultsMap, parentIds);

        }

        // find all the nodes that are parents (i.e. should be expanded)
        // any nodes that aren't parents are leaves in the tree, we need to check these leaf nodes to see if they have further children

        Collection<JsTreeObject> jsTreeObjects = new HashSet<>();
        for (String key : jsTreeObjectMap.keySet()) {
            for (JsTreeObject jsTreeObject : jsTreeObjectMap.get(key)) {

                if (jsTreeObject.getIri().equals(iri)) {
                    jsTreeObject.getState().put("selected", true);
                }

                if (!parentIds.contains(jsTreeObject.getId())) {
                    // this is a leaf node

                    if (!jsTreeObject.isLeaf()) {
                        jsTreeObject.setChildren(true);
                        jsTreeObject.getState().put("opened", false);
                    }
                }
            }

            jsTreeObjects.addAll(jsTreeObjectMap.get(key));
        }

        return jsTreeObjects;
    }

    private void generateJsTreeObject(String nodeId, String ontologyName, Map<String, Collection<JsTreeObject>> jsTreeObjectMap, Map<String, Map<String, Object>> resultsMap, Collection<String> parentIdCollector) {
        try {
            Collection<JsTreeObject> objectVersions = getJsObjectTree(nodeId, ontologyName, resultsMap, jsTreeObjectMap, parentIdCollector);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Collection<JsTreeObject> getJsObjectTree(String nodeId, String ontologyName, Map<String, Map<String, Object>> resultsMap, Map<String, Collection<JsTreeObject>> jsTreeObjectMap, Collection<String> parentIdCollector) throws IOException {
        if (jsTreeObjectMap.containsKey(nodeId)) {
            return jsTreeObjectMap.get(nodeId);
        }

        // if no key, then we are at a root
        if (!resultsMap.containsKey(nodeId)) {
            return Collections.singleton(new JsTreeObject("#", "#", ontologyName, "Thing", false, "#"));
        }

        Map<String, Object> row = resultsMap.get(nodeId);

        List<Integer> parentIds = new ObjectMapper().readValue(row.get("parents").toString(), List.class);

        int x = 1;
        for (Integer pid : parentIds) {

            String parentId = pid.toString();

            for (JsTreeObject parentObject : getJsObjectTree(parentId, ontologyName, resultsMap, jsTreeObjectMap, parentIdCollector)) {

                String startIri = row.get("startIri").toString();
                String startLabel = row.get("startLabel").toString();
                boolean isLeafNode = Boolean.parseBoolean(row.get("isLeaf").toString());

                String startNode = nodeId + "_" + x;


                JsTreeObject jsTreeObject = new JsTreeObject(
                        startNode,
                        startIri,
                        ontologyName,
                        startLabel,
                        isLeafNode,
                        parentObject.getId()
                );

                if (!jsTreeObjectMap.containsKey(nodeId)) {
                    jsTreeObjectMap.put(nodeId, new HashSet<>());
                }
                jsTreeObjectMap.get(nodeId).add(jsTreeObject);
                parentIdCollector.add(parentObject.getId());
                x++;
            }
        }
        return jsTreeObjectMap.get(nodeId);
    }


    public Page<Term> findAllByOntology(String ontologyId, Pageable pageable) {
        return termRepository.findAllByOntology(ontologyId, pageable);
    }

    public Term findByOntologyAndIri(String ontologyname, String iri) {
        return termRepository.findByOntologyAndIri(ontologyname, iri);
    }

    public Page<Term> getParents(String ontologyName, String iri, Pageable pageable) {
        return termRepository.getParents(ontologyName, iri, pageable);
    }

    public Page<Term> getChildren(String ontologyName, String iri, Pageable pageable) {
        return termRepository.getChildren(ontologyName, iri, pageable);
    }

    public Page<Term> getDescendants(String ontologyName, String iri, Pageable pageable) {
        return termRepository.getDescendants(ontologyName, iri, pageable);
    }

    public Page<Term> getAncestors(String ontologyName, String iri, Pageable pageable) {
        return termRepository.getAncestors(ontologyName, iri, pageable);
    }

    public Page<Term> getRelated(String ontologyId, String iri, String relation, Pageable pageable) {
        return termRepository.getRelated(ontologyId, iri, relation, pageable);
    }

    public Term findByOntologyAndShortForm(String ontologyId, String shortForm) {
        return termRepository.findByOntologyAndShortForm(ontologyId, shortForm);
    }
    public Term findByOntologyAndOboId(String ontologyId, String oboId) {
        return termRepository.findByOntologyAndOboId(ontologyId, oboId);
    }

    private class JsTreeObject {

        private String id;
        private String parent;
        private String iri;
        private String ontologyName;
        private String text;
        private boolean isLeaf;
                private Map<String, Boolean> state;
        private boolean children;


        public JsTreeObject(String id, String iri, String ontologyName, String text, boolean isLeaf, String parent) {
            this.id = id;
            this.iri = iri;
            this.ontologyName = ontologyName;
            this.text = text;
            this.isLeaf = isLeaf;
            this.parent = parent;
            this.state = new HashMap<>();
            state.put("opened", true);
            this.children = false;

        }

        public boolean isChildren() {
            return children;
        }

        public void setChildren(boolean children) {
            this.children = children;
        }


        public void setState(Map<String, Boolean> state) {
            this.state = state;
        }

        public Map<String, Boolean> getState() {
            return state;
        }

        public String getParent() {
            return parent;
        }

        public void setParent(String parent) {
            this.parent = parent;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getIri() {
            return iri;
        }

        public void setIri(String iri) {
            this.iri = iri;
        }

        public String getOntologyName() {
            return ontologyName;
        }

        public void setOntologyName(String ontologyName) {
            this.ontologyName = ontologyName;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public boolean isLeaf() {
            return isLeaf;
        }

        public void setIsLeaf(boolean isLeaf) {
            this.isLeaf = isLeaf;
        }
    }
}
