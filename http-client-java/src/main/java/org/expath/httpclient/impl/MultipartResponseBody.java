/****************************************************************************/
/*  File:       MultipartResponseBody.java                                  */
/*  Author:     F. Georges - fgeorges.org                                   */
/*  Date:       2009-02-04                                                  */
/*  Tags:                                                                   */
/*      Copyright (c) 2009 Florent Georges (see end of file.)               */
/* ------------------------------------------------------------------------ */


package org.expath.httpclient.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.james.mime4j.MimeException;
import org.apache.james.mime4j.field.AbstractField;
import org.apache.james.mime4j.parser.Field;
import org.apache.james.mime4j.parser.MimeTokenStream;
import org.expath.httpclient.ContentType;
import org.expath.httpclient.HeaderSet;
import org.expath.httpclient.HttpClientException;
import org.expath.httpclient.HttpConnection;
import org.expath.httpclient.HttpResponseBody;
import org.expath.httpclient.model.Result;
import org.expath.httpclient.model.TreeBuilder;

/**
 * TODO<doc>: ...
 *
 * @author Florent Georges
 * @date   2009-02-04
 */
public class MultipartResponseBody
        implements HttpResponseBody
{
    private static Log LOG = LogFactory.getLog(MultipartResponseBody.class);
    
    private List<HttpResponseBody> myParts;
    private ContentType myContentType;
    private String myBoundary;

    
    public MultipartResponseBody(final Result result, final InputStream in, final ContentType type, final HttpConnection conn)
            throws HttpClientException
    {
        myContentType = type;
        myParts = new ArrayList<HttpResponseBody>();
        final Header h = conn.getResponseHeaders().getFirstHeader(ContentType.CONTENT_TYPE_HEADER);
        if ( h == null ) {
            throw new HttpClientException("No content type");
        }
        myBoundary = type.getBoundary();
        if ( myBoundary == null ) {
            throw new HttpClientException("No boundary");
        }
        try {
            analyzeParts(result, in, h.getValue());
        }
        catch ( final IOException ex ) {
            throw new HttpClientException("error reading the response stream", ex);
        }
    }

    @Override
    public void outputBody(final TreeBuilder b)
            throws HttpClientException
    {
        b.startElem("multipart");
        b.attribute("media-type", myContentType.getValue());
        b.attribute("boundary", myBoundary);
        b.startContent();
        for ( final HttpResponseBody part : myParts ) {
            part.outputBody(b);
        }
        b.endElem();
    }

    private void analyzeParts(final Result result, final InputStream in, final String type)
            throws IOException
                 , HttpClientException
    {
        final MimeTokenStream parser = new MimeTokenStream();
        parser.parseHeadless(in, type);
        try {
            HeaderSet headers = null;
            for ( int state = parser.getState();
                  state != MimeTokenStream.T_END_OF_STREAM;
                  state = parser.next() )
            {
                if ( state == MimeTokenStream.T_START_HEADER ) {
                    headers = new HeaderSet();
                }
                handleParserState(result, parser, headers);
            }
        }
        catch ( final MimeException ex ) {
            throw new HttpClientException("The response content is ill-formed.", ex);
        }
    }

    private void handleParserState(final Result result, final MimeTokenStream parser, final HeaderSet headers)
            throws HttpClientException
    {
        final int state = parser.getState();
        if ( LOG.isDebugEnabled() ) {
            LOG.debug(MimeTokenStream.stateToString(state));
        }
        switch ( state ) {
            // It seems that in a headless parsing, END_HEADER appears
            // right after START_MESSAGE (without the corresponding
            // START_HEADER).  So if headers == null, we can just ignore
            // this state.
            case MimeTokenStream.T_END_HEADER:
                // TODO: Just ignore anyway...?
                break;
                
            case MimeTokenStream.T_FIELD:
                final Field f = parser.getField();
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug("  field: " + f);
                }
                headers.add(f.getName(), parseFieldBody(f));
                break;
                
            case MimeTokenStream.T_BODY:
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug("  body desc: " + parser.getBodyDescriptor());
                }
                final HttpResponseBody b = makeResponsePart(result, headers, parser);
                myParts.add(b);
                break;
                
            // START_HEADER is handled in the calling analyzeParts()
            case MimeTokenStream.T_START_HEADER:
            case MimeTokenStream.T_END_BODYPART:
            case MimeTokenStream.T_END_MESSAGE:
            case MimeTokenStream.T_END_MULTIPART:
            case MimeTokenStream.T_EPILOGUE:
            case MimeTokenStream.T_PREAMBLE:
            case MimeTokenStream.T_START_BODYPART:
            case MimeTokenStream.T_START_MESSAGE:
            case MimeTokenStream.T_START_MULTIPART:
                // ignore
                break;
            
                // In a first time, take a very defensive approach, and
            // throw an error for all unexpected states, even if we
            // should discover slowly that we should probably just
            // ignore some of them.
            default:
                final String s = MimeTokenStream.stateToString(state);
                throw new HttpClientException("Unknown parsing state: " + s);
        }
    }

    private String parseFieldBody(final Field f)
            throws HttpClientException
    {
        try {
            final String b = AbstractField.parse(f.getRaw()).getBody();
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("Field: " + f.getName() + ": [" + b + "]");
            }
            return b;
        }
        catch ( final MimeException ex ) {
            LOG.error("Field value parsing error (" + f + ")", ex);
            throw new HttpClientException("Field value parsing error (" + f + ")", ex);
        }
    }

    private HttpResponseBody makeResponsePart(final Result result, final HeaderSet headers, final MimeTokenStream parser)
            throws HttpClientException
    {
        final Header h = headers.getFirstHeader(ContentType.CONTENT_TYPE_HEADER);
        if ( h == null ) {
            throw new HttpClientException("impossible to find the content type");
        }
        
        final HttpResponseBody part;
        final ContentType type = new ContentType(h);
        switch ( BodyFactory.parseType(type) ) {
            case XML: {
                // TODO: 'content_type' is the header Content-Type without any
                // param (i.e. "text/xml".)  Should we keep this, or put the
                // whole header (i.e. "text/xml; charset=utf-8")? (and for
                // other types as well...)
                final Reader in = parser.getReader();
                part = new XmlResponseBody(result, in, type, headers, false);
                break;
            }
                
            case HTML: {
                final Reader in = parser.getReader();
                part = new XmlResponseBody(result, in, type, headers, true);
                break;
            }
                
            case TEXT: {
                final InputStream in = parser.getInputStream();
                part = new TextResponseBody(result, in, type, headers);
                break;
            }
                
            case BINARY: {
                final InputStream in = parser.getInputStream();
                part = new BinaryResponseBody(result, in, type, headers);
                break;
            }
            default:
                throw new HttpClientException("INTERNAL ERROR: cannot happen");
        }
        
        return part;
    }
}


/* ------------------------------------------------------------------------ */
/*  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS COMMENT.               */
/*                                                                          */
/*  The contents of this file are subject to the Mozilla Public License     */
/*  Version 1.0 (the "License"); you may not use this file except in        */
/*  compliance with the License. You may obtain a copy of the License at    */
/*  http://www.mozilla.org/MPL/.                                            */
/*                                                                          */
/*  Software distributed under the License is distributed on an "AS IS"     */
/*  basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.  See    */
/*  the License for the specific language governing rights and limitations  */
/*  under the License.                                                      */
/*                                                                          */
/*  The Original Code is: all this file.                                    */
/*                                                                          */
/*  The Initial Developer of the Original Code is Florent Georges.          */
/*                                                                          */
/*  Contributor(s): Adam Retter                                             */
/* ------------------------------------------------------------------------ */
