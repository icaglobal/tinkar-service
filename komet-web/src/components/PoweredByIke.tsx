import poweredByIke from '../assets/powered-by-ike.png';

/** The "Powered by IKE" mark, linking to IKE's site as Komet desktop's does. */
export function PoweredByIke({ className }: { className?: string }) {
  return (
    <a className={className} href="https://ike.network" target="_blank" rel="noreferrer">
      <img src={poweredByIke} alt="Powered by IKE" />
    </a>
  );
}
