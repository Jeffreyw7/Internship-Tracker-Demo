import {useEffect,useState,useSyncExternalStore} from 'react';
import {applicationImportApi} from '../data/applicationImportApi';
import {ApiError,sessionGuard} from '../data/api';
import {createImportWorkflow} from '../domain/importWorkflow';
export function useApplicationImport() {
  const [controller]=useState(()=>createImportWorkflow({guard:sessionGuard,preview:applicationImportApi.preview,status:applicationImportApi.status,terminal:error=>error instanceof ApiError&&error.authoritative?error.status===404?'MISSING':error.status===410?'EXPIRED':null:null}));
  const snapshot=useSyncExternalStore(controller.subscribe,controller.getSnapshot,controller.getSnapshot);
  useEffect(()=>{controller.activate();return()=>controller.dispose();},[controller]);
  return {...snapshot,edit:controller.edit,chooseFile:controller.chooseFile,updateRow:controller.updateRow,createPreview:controller.createPreview,recoverPreview:controller.recoverPreview,restartReview:controller.restartReview};
}
