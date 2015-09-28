
package org.gazzax.labs.solrdf.handler.search.algebra;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static org.gazzax.labs.solrdf.NTriples.asNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.QParser;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SyntaxError;
import org.gazzax.labs.solrdf.Field;
import org.gazzax.labs.solrdf.NTriples;
import org.gazzax.labs.solrdf.Names;
import org.gazzax.labs.solrdf.graph.standalone.LocalGraph;
import org.gazzax.labs.solrdf.log.Log;
import org.gazzax.labs.solrdf.log.MessageCatalog;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.sparql.core.BasicPattern;
import com.hp.hpl.jena.sparql.core.Var;
import com.hp.hpl.jena.sparql.engine.ExecutionContext;
import com.hp.hpl.jena.sparql.engine.QueryIterator;
import com.hp.hpl.jena.sparql.engine.binding.Binding;
import com.hp.hpl.jena.sparql.engine.binding.BindingFactory;
import com.hp.hpl.jena.sparql.engine.binding.BindingMap;
import com.hp.hpl.jena.sparql.engine.iterator.QueryIter1;

/**
 * A {@link QueryIterator} implementation for executing {@link BasicPattern}s.
 * 
 * @author Andrea Gazzarini
 * @since 1.0
 */
public class QueryIterBasicGraphPattern2 extends QueryIter1 {
	private final static Log LOGGER = new Log(LoggerFactory.getLogger(QueryIterBasicGraphPattern2.class));
	
	final static List<Binding> EMPTY_BINDINGS = Collections.emptyList();
	final static DocSetAndTriplePattern EMPTY_DOCSET = new DocSetAndTriplePattern(new EmptyDocSet(), null, null);
	final static List<DocSetAndTriplePattern> NULL_DOCSETS = new ArrayList<DocSetAndTriplePattern>(2);
	static {
		NULL_DOCSETS.add(EMPTY_DOCSET);
		NULL_DOCSETS.add(EMPTY_DOCSET);
	}
    
    private DocSetAndTriplePattern master;
    private DocIterator masterIterator;
    private List<DocSetAndTriplePattern> subsequents;
    private Iterator<DocSetAndTriplePattern> dstpIterator;
    
    List<DocSetAndTriplePattern> docsets;
    BasicPattern bgp;
    
    // FIXME: MUNNEZZ
    public QueryIterBasicGraphPattern2 mergeWith(final QueryIterBasicGraphPattern2 next) {
    	if (next.docsets == null || next.docsets.isEmpty() || next.docsets.iterator().next().size() == 0) {
    		docsets = NULL_DOCSETS;
    	}
    	
    	docsets.addAll(next.docsets);
    	docsets = docsets.stream().sorted(comparing(DocSetAndTriplePattern::size)).collect(toList());
    	
    	master = docsets.get(0);
		masterIterator = master.children.iterator();
		subsequents = docsets.subList(1, docsets.size());
		dstpIterator = subsequents.iterator();
		
    	return this;
    }
    
    /**
     * Builds a new iterator with the given data.
     * 
     * @param bgp the Basic Graph Pattern.
     * @param context the execution context.
     */
    public QueryIterBasicGraphPattern2(
    		final QueryIterator input,
    		final BasicPattern bgp, 
    		final ExecutionContext context) {
        super(input, context) ;
        this.bgp = bgp;
		try {
			final List<Triple> patterns = 
					input instanceof QueryIterBasicGraphPattern2 
						? Stream.concat(
								bgp.getList().stream(), 
								((QueryIterBasicGraphPattern2)input).bgp.getList().stream())
							.collect(toList())
						: bgp.getList();	
					
			docsets = docsets(
					patterns, 
					(SolrQueryRequest)context.getContext().get(Names.SOLR_REQUEST_SYM),
					(LocalGraph)context.getActiveGraph());
			master = docsets.get(0);
			masterIterator = master.children.iterator();
			subsequents = docsets.subList(1, docsets.size());
			dstpIterator = subsequents.iterator();
		} catch (final Exception exception) {
			LOGGER.error(MessageCatalog._00113_NWS_FAILURE, exception);
			master = EMPTY_DOCSET;
			masterIterator = master.children.iterator();
			subsequents = Collections.emptyList();
			dstpIterator = subsequents.iterator();
		}
    }
    	
    private List<Binding> bindings = new ArrayList<Binding>();
    private Iterator<Binding> bindingsIterator;
    
    /**
     * Executes a join between the given {@link Document} identifier and a {@link DocSet}.
     * 
     * @param parentBinding the parent binding (null in case of root binding).
     * @param docId the {@link Document} identifier.
     * @param currentTriplePattern the current {@link Triple} pattern.
     * @param rawDataBeforeJoin the target {@link DocSet}.
     * @param searcher the Solr searcher.
     * @throws IOException in case of I/O exception.
     */
	void join(
			final Binding parentBinding,
			final int docId,
			final Triple currentTriplePattern,
			final DocSetAndTriplePattern rawDataBeforeJoin, 
			final SolrIndexSearcher searcher) throws IOException {
		
		final BindingMap binding = parentBinding != null 
				? BindingFactory.create(parentBinding) 
				: BindingFactory.create();
		
		final Document triple = searcher.doc(docId);
		
		bind(currentTriplePattern.getSubject(), binding, triple, Field.S);
		bind(currentTriplePattern.getPredicate(), binding, triple, Field.P);
		bind(currentTriplePattern.getObject(), binding, triple, Field.O);
		
		final BooleanQuery query = buildJoinQuery(rawDataBeforeJoin.pattern, binding);
		final DocIterator iterator = 
				query.clauses().isEmpty() 
					? rawDataBeforeJoin.children.iterator() 
					: searcher.getDocSet(query, rawDataBeforeJoin.children).iterator();
		
		if (dstpIterator.hasNext()) {
			final DocSetAndTriplePattern next = dstpIterator.next();
			while (iterator.hasNext()) {
				join(binding, iterator.nextDoc(), rawDataBeforeJoin.pattern, next, searcher);				
			}
		} else {
			while (iterator.hasNext()) {
				bindings.add(
						leafBinding(
								binding, 
								iterator.nextDoc(), 
								rawDataBeforeJoin.pattern, 
								searcher));
			}
		}
	}
	
	/**
	 * Builds the query needed to join and refine the next triple pattern {@link DocSet}.
	 * 
	 * @param pattern the triple pattern.
	 * @param binding the current binding.
	 * @return the query needed to join and refine the next triple pattern {@link DocSet}.
	 */
	BooleanQuery buildJoinQuery(final Triple pattern, final Binding binding) {
		final BooleanQuery query = new BooleanQuery();
		addClause(pattern.getSubject(), query, binding, Field.S);
		addClause(pattern.getPredicate(), query, binding, Field.P);
		addClause(pattern.getObject(), query, binding, Field.O);
		return query;
	}
	
	/**
	 * Adds a mandtory clause to the incoming query (depending on the input node and binding).
	 * If the incoming node is a variable and the binding already contains a value for that variable, then
	 * a new (mandatory) clause will be added to the given {@link BooleanQuery}.
	 * 
	 * @param member the current {@link Node}, which is a member of the current {@link Triple} pattern.
	 * @param query the {@link BooleanQuery} that needs to collect clauses.
	 * @param binding the current binding.
	 * @param fieldName the {@link SolrDocument} field name that corresponds to the given member.
	 */
	void addClause(final Node member, final BooleanQuery query, final Binding binding, final String fieldName) {
		if (member.isVariable()) {
			final Var var = Var.alloc(member);
			if (binding.contains(var)) {
				query.add(
						new TermQuery(
							new Term(
									Field.S, 
									NTriples.asNt(binding.get(var)))), Occur.MUST);		
			}
		}
	}
	
	/**
	 * Creates a leaf binding.
	 * A leaf binding is the last ring of the binding chain, and therefore, it will be returned 
	 * to the client, as product of the {@link QueryIterator} interface.
	 * 
	 * @param parentBinding the parent binding in this execution chain.
	 * @param current the identifier of the current {@link Document} (representing a {@link Triple}).
	 * @param pattern the {@link Triple} pattern that originated the current {@link Document} identifier.
	 * @param searcher the Solr searcher.
	 * @throws IOException in case of I/O failure.
	 * @return a leaf {@link Binding}.
	 */
	Binding leafBinding(
			final Binding parentBinding,
			final int current,
			final Triple pattern,
			final SolrIndexSearcher searcher) throws IOException {
		
		final Document triple = searcher.doc(current);
		final BindingMap binding = parentBinding != null 
				? BindingFactory.create(parentBinding) 
				: BindingFactory.create();

		bind(pattern.getSubject(), binding, triple, Field.S);
		bind(pattern.getPredicate(), binding, triple, Field.P);
		bind(pattern.getObject(), binding, triple, Field.O);
		
		System.out.println("*******************");
		Iterator<Var> iterator = binding.vars();
		while (iterator.hasNext()) {
			Var var = iterator.next();
			System.out.println(var.getName() + " = " + binding.get(var));
		}
		
		
		return binding;
	}
	
	void bind(
			final Node member, 
			final BindingMap binding,
			final Document triple, 
			final String fieldName) {
		if (member.isVariable()) {
			final Var var = Var.alloc(member);
			if (!binding.contains(var)) {
				binding.add(var, asNode(triple.get(fieldName)));
			}
		}
	}	
	
	/**
	 * Returns the list of {@link PatternDocSet} coming from the execution of all triple patterns with the BGP.
	 * 
	 * @param bgp the basic graph pattern.
	 * @param request the current Solr request.
	 * @param graph the active graph.
	 * @return the list of {@link PatternDocSet} coming from the execution of all triple patterns with the BGP.
	 */
	List<DocSetAndTriplePattern> docsets(final List<Triple> patterns, final SolrQueryRequest request, final LocalGraph graph) {
		final List<DocSetAndTriplePattern> docsets = patterns.parallelStream()
				.map(triplePattern ->  {
					try {
						final BooleanQuery query = new BooleanQuery();
						query.add(
								QParser.getParser(
										graph.luceneQuery(triplePattern), 
										Names.SOLR_QPARSER, 
										request)
										.getQuery(), 
								Occur.MUST);
						
						return new DocSetAndTriplePattern(request.getSearcher().getDocSet(query), triplePattern, query);
					} catch (final IOException exception) {
						LOGGER.error(MessageCatalog._00118_IO_FAILURE, exception);
						return new DocSetAndTriplePattern(new EmptyDocSet(), triplePattern, null);
					} catch (final SyntaxError exception) {
						LOGGER.error(MessageCatalog._00119_QUERY_PARSING_FAILURE, exception);
						return new DocSetAndTriplePattern(new EmptyDocSet(), triplePattern, null);
					} catch (final Exception exception) {
						LOGGER.error(MessageCatalog._00113_NWS_FAILURE, exception);
						return new DocSetAndTriplePattern(new EmptyDocSet(), triplePattern, null);
					}										
				})
			.sorted(comparing(DocSetAndTriplePattern::size))	
			.collect(toList());
		
		if (LOGGER.isDebugEnabled()) {
			final long tid = System.currentTimeMillis();
			docsets.stream().forEach(
					data -> LOGGER.debug(
								MessageCatalog._00120_BGP_EXPLAIN, 
								tid, 
								data.pattern, 
								data.query, 
								data.children.size()));
		}
		
		return (docsets.size() > 0 && docsets.iterator().next().size() > 0) ? docsets : NULL_DOCSETS;
	}
	
    @Override
    protected boolean hasNextBinding() {
    	if (bindingsIterator != null && bindingsIterator.hasNext()) {
    		return true;
    	}
    	
        try {
	    	final SolrQueryRequest request = (SolrQueryRequest)getExecContext().getContext().get(Names.SOLR_REQUEST_SYM);
			final SolrIndexSearcher searcher = (SolrIndexSearcher) request.getSearcher();
			
	        while (masterIterator.hasNext()) {
	        	bindings.clear();
	        	final int docId = masterIterator.next();
	        	dstpIterator = subsequents.iterator(); 
	        	if (dstpIterator.hasNext()) {   
	        		join(null, docId, master.pattern, dstpIterator.next(), searcher);
	        	} else {
	        		bindings.add(leafBinding(null, docId, master.pattern, searcher));
	        	}
	        	
	        	if (!bindings.isEmpty()) {
	    	        bindingsIterator = bindings.iterator();	        		
	    	        return true;
	        	}
	        }
	        return false;
        } catch (final Exception exception) {
        	throw new RuntimeException(exception);
        }
    }

    @Override
    protected Binding moveToNextBinding() {
        return bindingsIterator.next();
    }

	@Override
	protected void requestSubCancel() {
		// TODO Auto-generated method stub
	}

	@Override
	protected void closeSubIterator() {
		// TODO Auto-generated method stub
	}
}